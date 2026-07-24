package com.fingerly.app.highway

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.fingerly.app.log.RemoteLog
import com.fingerly.core.midi.MidiEvent
import com.fingerly.core.midi.MidiEventHandler
import com.fingerly.core.midi.MidiEventRing
import com.fingerly.core.play.HitJudge
import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import java.util.Random

/**
 * Falling-note highway (SPEC §7 Phase 2): custom Canvas render layer, Synthesia
 * orientation — keys across the bottom, notes fall down, a note reaches the hit
 * line exactly at its chart time. Live hit/miss via [HitJudge], micro-rewards via
 * a pooled particle burst per hit (SPEC §6).
 *
 * Hot-path rules (SPEC §1): all pools and paints are pre-allocated; the frame
 * loop performs no allocation and no I/O. HUD strings are rebuilt only when the
 * numbers change. Frame drops are counted and surfaced — the Phase 2 acceptance
 * gate is zero dropped frames at 120Hz with 200 live particles (perf test mode).
 */
@SuppressLint("ViewConstructor")
class NoteHighwayView(
    context: Context,
    private val ring: MidiEventRing,
    private val score: Score,
    /** Injects a synthetic MIDI event (type, note, velocity) — see [autoplay]. */
    private val inject: (Int, Int, Int) -> Unit = { _, _, _ -> },
) : View(context), Choreographer.FrameCallback {

    private val judge = HitJudge(score.notes)
    private val notes: List<ChartNote> = score.notes

    /**
     * Autoplay: the chart plays itself through the real MIDI pipeline (inject →
     * ring → judge), so sync, hit detection, and rewards can be verified without
     * any piano skill. Also how "listen before you play" will work later (§3).
     */
    var autoplay = false
    private val autoOnSent = BooleanArray(notes.size)
    private val autoOffSent = BooleanArray(notes.size)
    private var autoFrom = 0

    // --- clock ---
    private var anchorNanos = 0L
    private var songMs = -LEAD_IN_MS
    private var ended = false
    private var running = false

    // --- keyboard geometry (precomputed in onSizeChanged) ---
    private var lowNote = 48
    private var highNote = 84
    private val noteX = FloatArray(128) // center x per midi note
    private val noteW = FloatArray(128)
    private var keyboardTop = 0f
    private var whiteKeyWidth = 0f
    private val pressed = BooleanArray(128)

    // --- notes draw state ---
    private var drawFrom = 0
    private val noteRect = RectF()

    // --- particles (structure-of-arrays pool) ---
    private val px = FloatArray(MAX_PARTICLES)
    private val py = FloatArray(MAX_PARTICLES)
    private val pvx = FloatArray(MAX_PARTICLES)
    private val pvy = FloatArray(MAX_PARTICLES)
    private val plife = FloatArray(MAX_PARTICLES)
    private var particleCount = 0
    private val random = Random(42)
    var perfTestMode = false

    // --- frame stats ---
    private var lastFrameNanos = 0L
    private var frameIntervalNanos = 8_333_333L
    private var droppedFrames = 0
    private var worstFrameMs = 0f
    private var emaFps = 0f
    private var lastHudNanos = 0L
    private var lastLogNanos = 0L
    private var lastJudgedTotal = -1

    // --- paints ---
    private val whiteKeyPaint = Paint().apply { color = Color.rgb(240, 240, 235) }
    private val blackKeyPaint = Paint().apply { color = Color.rgb(24, 30, 36) }
    private val pressedPaint = Paint().apply { color = Color.rgb(0, 230, 118) }
    private val hitLinePaint = Paint().apply {
        color = Color.rgb(0, 230, 118)
        strokeWidth = 3f
    }
    private val rightNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
    private val leftNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 196, 255) }
    private val hitNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 240) }
    private val missNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 40, 40) }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 215)
        textSize = 34f
        typeface = Typeface.MONOSPACE
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        textSize = 64f
        typeface = Typeface.MONOSPACE
    }

    private val hudLine = StringBuilder(96)
    private val perfLine = StringBuilder(96)
    private val endLine1 = StringBuilder(96)
    private val endLine2 = StringBuilder(96)

    private val drainHandler = MidiEventHandler { event ->
        when (event.type) {
            MidiEvent.TYPE_NOTE_ON -> {
                if (event.data1 < 128) pressed[event.data1] = true
                if (!ended) {
                    val evSongMs = (event.timestampNanos - anchorNanos) / 1_000_000 - LEAD_IN_MS
                    val idx = judge.onNoteOn(event.data1, evSongMs)
                    if (idx >= 0) spawnBurst(noteX[event.data1], keyboardTop, HIT_BURST)
                }
            }

            MidiEvent.TYPE_NOTE_OFF -> if (event.data1 < 128) pressed[event.data1] = false
        }
    }

    init {
        rebuildHud()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        restart()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    fun restart() {
        judge.reset()
        anchorNanos = System.nanoTime()
        songMs = -LEAD_IN_MS
        ended = false
        drawFrom = 0
        particleCount = 0
        droppedFrames = 0
        worstFrameMs = 0f
        lastJudgedTotal = -1
        autoFrom = 0
        java.util.Arrays.fill(autoOnSent, false)
        java.util.Arrays.fill(autoOffSent, false)
        java.util.Arrays.fill(pressed, false)
        rebuildHud()
    }

    /** Emits due autoplay note-ons/offs into the MIDI pipeline. Allocation-free. */
    private fun pumpAutoplay() {
        var i = autoFrom
        while (i < notes.size) {
            val startMs = (notes[i].startSeconds * 1000).toLong()
            if (startMs > songMs) break
            if (!autoOnSent[i]) {
                autoOnSent[i] = true
                inject(MidiEvent.TYPE_NOTE_ON, notes[i].midiNote, AUTOPLAY_VELOCITY)
            }
            if (!autoOffSent[i] &&
                startMs + (notes[i].durationSeconds * 1000).toLong() <= songMs
            ) {
                autoOffSent[i] = true
                inject(MidiEvent.TYPE_NOTE_OFF, notes[i].midiNote, 0)
            }
            i++
        }
        while (autoFrom < notes.size && autoOffSent[autoFrom]) autoFrom++
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyboardTop = h * (1f - KEYBOARD_FRACTION)

        var min = 127
        var max = 0
        for (n in notes) {
            if (n.midiNote < min) min = n.midiNote
            if (n.midiNote > max) max = n.midiNote
        }
        if (min > max) { min = 60; max = 72 }
        lowNote = whiteKeyAtOrBelow(min - 2)
        highNote = whiteKeyAtOrAbove(max + 2)

        var whiteCount = 0
        for (n in lowNote..highNote) if (!isBlack(n)) whiteCount++
        whiteKeyWidth = w.toFloat() / whiteCount

        var whiteIndex = 0
        for (n in lowNote..highNote) {
            if (!isBlack(n)) {
                noteX[n] = (whiteIndex + 0.5f) * whiteKeyWidth
                noteW[n] = whiteKeyWidth * 0.86f
                whiteIndex++
            } else {
                noteX[n] = whiteIndex * whiteKeyWidth // boundary between whites
                noteW[n] = whiteKeyWidth * 0.58f
            }
        }
    }

    // ------------------------------------------------------------------ frame loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val refresh = display?.refreshRate ?: 0f
        if (refresh > 1f) frameIntervalNanos = (1_000_000_000f / refresh).toLong()

        // Frame pacing stats (perf gate).
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            val deltaMs = delta / 1_000_000f
            if (deltaMs > worstFrameMs) worstFrameMs = deltaMs
            if (delta > frameIntervalNanos * 3 / 2) droppedFrames++
            val fps = 1_000_000_000f / delta
            emaFps = if (emaFps == 0f) fps else emaFps * 0.95f + fps * 0.05f
        }
        lastFrameNanos = frameTimeNanos

        songMs = (frameTimeNanos - anchorNanos) / 1_000_000 - LEAD_IN_MS
        if (autoplay && !ended) pumpAutoplay()
        ring.drain(drainHandler)
        if (!ended) {
            judge.advanceTo(songMs)
            if (songMs > score.totalSeconds * 1000 + END_GRACE_MS) {
                ended = true
                rebuildEndText()
                logSummary(force = true)
            }
        }

        updateParticles(frameIntervalNanos / 1_000_000_000f)
        if (perfTestMode && particleCount < PERF_TARGET_PARTICLES) {
            spawnBurst(
                x = whiteKeyWidth + random.nextFloat() * (width - 2 * whiteKeyWidth),
                y = keyboardTop * random.nextFloat(),
                count = 32,
            )
        }

        val judged = judge.hits + judge.misses + judge.extras
        if (judged != lastJudgedTotal) {
            lastJudgedTotal = judged
            rebuildHud()
        }
        if (frameTimeNanos - lastHudNanos > 1_000_000_000L) {
            lastHudNanos = frameTimeNanos
            rebuildPerfLine(refresh)
        }
        logSummaryMaybe(frameTimeNanos)

        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawNotes(canvas)
        drawParticles(canvas)
        drawKeyboard(canvas)
        canvas.drawLine(0f, keyboardTop, width.toFloat(), keyboardTop, hitLinePaint)
        canvas.drawText(hudLine, 0, hudLine.length, 24f, 48f, hudPaint)
        canvas.drawText(perfLine, 0, perfLine.length, 24f, 96f, hudPaint)
        if (ended) {
            canvas.drawText(endLine1, 0, endLine1.length, width * 0.30f, height * 0.38f, bigPaint)
            canvas.drawText(endLine2, 0, endLine2.length, width * 0.30f, height * 0.38f + 80f, hudPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
        val pps = keyboardTop / (LOOKAHEAD_MS / 1000f) // pixels per second
        val nowSec = songMs / 1000f
        // Skip fully-consumed notes permanently.
        while (drawFrom < notes.size &&
            (notes[drawFrom].startSeconds + notes[drawFrom].durationSeconds) * 1000 < songMs - 200
        ) {
            drawFrom++
        }
        var i = drawFrom
        while (i < notes.size) {
            val n = notes[i]
            val startIn = n.startSeconds.toFloat() - nowSec
            if (startIn * 1000 > LOOKAHEAD_MS) break
            val endIn = startIn + n.durationSeconds.toFloat()
            var top = keyboardTop - endIn * pps
            var bottom = keyboardTop - startIn * pps
            if (bottom > keyboardTop) bottom = keyboardTop
            if (top < 0f) top = 0f
            if (bottom > top) {
                val paint = when (judge.stateOf(i)) {
                    HitJudge.STATE_HIT -> hitNotePaint
                    HitJudge.STATE_MISSED -> missNotePaint
                    else -> if (n.hand == ChartNote.HAND_RIGHT) rightNotePaint else leftNotePaint
                }
                val half = noteW[n.midiNote] / 2f
                noteRect.set(noteX[n.midiNote] - half, top, noteX[n.midiNote] + half, bottom)
                canvas.drawRoundRect(noteRect, 8f, 8f, paint)
            }
            i++
        }
    }

    private fun drawKeyboard(canvas: Canvas) {
        val h = height.toFloat()
        // White keys first.
        for (n in lowNote..highNote) {
            if (isBlack(n)) continue
            val half = whiteKeyWidth / 2f - 1f
            noteRect.set(noteX[n] - half, keyboardTop + 2f, noteX[n] + half, h)
            canvas.drawRect(noteRect, if (pressed[n]) pressedPaint else whiteKeyPaint)
        }
        // Black keys on top, upper 60% of keyboard height.
        val blackBottom = keyboardTop + (h - keyboardTop) * 0.62f
        for (n in lowNote..highNote) {
            if (!isBlack(n)) continue
            val half = noteW[n] / 2f
            noteRect.set(noteX[n] - half, keyboardTop + 2f, noteX[n] + half, blackBottom)
            canvas.drawRect(noteRect, if (pressed[n]) pressedPaint else blackKeyPaint)
        }
    }

    // ------------------------------------------------------------------ particles

    private fun spawnBurst(x: Float, y: Float, count: Int) {
        var j = 0
        while (j < count && particleCount < MAX_PARTICLES) {
            val i = particleCount++
            px[i] = x
            py[i] = y
            pvx[i] = (random.nextFloat() - 0.5f) * 900f
            pvy[i] = -random.nextFloat() * 1100f - 150f
            plife[i] = 0.5f + random.nextFloat() * 0.5f
            j++
        }
    }

    private fun updateParticles(dt: Float) {
        var i = 0
        while (i < particleCount) {
            plife[i] -= dt
            if (plife[i] <= 0f) {
                val last = --particleCount
                px[i] = px[last]; py[i] = py[last]
                pvx[i] = pvx[last]; pvy[i] = pvy[last]
                plife[i] = plife[last]
                continue // re-check swapped-in slot
            }
            pvy[i] += GRAVITY * dt
            px[i] += pvx[i] * dt
            py[i] += pvy[i] * dt
            i++
        }
    }

    private fun drawParticles(canvas: Canvas) {
        var i = 0
        while (i < particleCount) {
            val alpha = (plife[i] * 255f).toInt().coerceIn(0, 255)
            particlePaint.alpha = alpha
            canvas.drawCircle(px[i], py[i], 5f, particlePaint)
            i++
        }
        particlePaint.alpha = 255
    }

    // ------------------------------------------------------------------ HUD / logs

    private fun rebuildHud() {
        hudLine.setLength(0)
        hudLine.append("acc ")
        appendPercent(hudLine, judge.accuracyPercent())
        hudLine.append("  hit ").append(judge.hits)
            .append("  miss ").append(judge.misses)
            .append("  extra ").append(judge.extras)
            .append("  combo ").append(judge.combo)
    }

    private fun rebuildPerfLine(refresh: Float) {
        perfLine.setLength(0)
        perfLine.append(refresh.toInt()).append("Hz fps ").append(emaFps.toInt())
            .append("  dropped ").append(droppedFrames)
            .append("  particles ").append(particleCount)
        if (perfTestMode) perfLine.append("  [PERF TEST]")
    }

    private fun rebuildEndText() {
        endLine1.setLength(0)
        endLine1.append("accuracy ")
        appendPercent(endLine1, judge.accuracyPercent())
        endLine2.setLength(0)
        endLine2.append("hit ").append(judge.hits)
            .append("  miss ").append(judge.misses)
            .append("  extra ").append(judge.extras)
            .append("  avg err ").append(judge.avgAbsErrorMs()).append("ms")
            .append("  best combo ").append(judge.bestCombo)
            .append("  — tap to restart")
    }

    private fun logSummaryMaybe(frameTimeNanos: Long) {
        if (!RemoteLog.isEnabled()) return
        if (lastLogNanos == 0L) lastLogNanos = frameTimeNanos
        if (frameTimeNanos - lastLogNanos < 5_000_000_000L) return
        lastLogNanos = frameTimeNanos
        logSummary(force = false)
    }

    private fun logSummary(force: Boolean) {
        if (!RemoteLog.isEnabled()) return
        RemoteLog.log(
            "highway",
            "t=${songMs / 1000}s fps=${emaFps.toInt()} dropped=$droppedFrames " +
                "worst=${worstFrameMs.toInt()}ms particles=$particleCount " +
                "acc=${judge.accuracyPercent().toInt()}% hit=${judge.hits} " +
                "miss=${judge.misses} extra=${judge.extras}" +
                (if (perfTestMode) " PERF" else "") + (if (force) " END" else ""),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ended) {
            restart()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ------------------------------------------------------------------ helpers

    private fun appendPercent(sb: StringBuilder, value: Float) {
        val tenths = (value * 10f).toInt()
        sb.append(tenths / 10).append('.').append(tenths % 10).append('%')
    }

    private fun isBlack(note: Int): Boolean = when (note % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    private fun whiteKeyAtOrBelow(note: Int): Int {
        var n = note.coerceIn(0, 127)
        while (isBlack(n)) n--
        return n
    }

    private fun whiteKeyAtOrAbove(note: Int): Int {
        var n = note.coerceIn(0, 127)
        while (isBlack(n)) n++
        return n
    }

    companion object {
        private const val LEAD_IN_MS = 3000L
        private const val END_GRACE_MS = 1500L
        private const val LOOKAHEAD_MS = 3000f
        private const val KEYBOARD_FRACTION = 0.20f
        private const val MAX_PARTICLES = 512
        private const val PERF_TARGET_PARTICLES = 200
        private const val HIT_BURST = 24
        private const val GRAVITY = 1500f
        private const val AUTOPLAY_VELOCITY = 80
    }
}
