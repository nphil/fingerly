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
    /** Widened at slow practice tempos so beginners aren't punished by ±150ms. */
    hitWindowMs: Long = 150,
    missAfterMs: Long = 300,
    /** Per-note: any octave of the letter counts (foundations home rung). */
    matchAnyOctave: BooleanArray? = null,
) : View(context), Choreographer.FrameCallback {

    private val judge = HitJudge(score.notes, hitWindowMs, missAfterMs, matchAnyOctave)
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

    /** Session mode: called once when the run completes; flow is external then. */
    var onEnded: ((HitJudge) -> Unit)? = null

    /** Attempt recording (SPEC §3): pre-allocated, song-relative event log. */
    var recordEnabled = false
    private val recTimesMs = IntArray(REC_CAPACITY)
    private val recTypes = ByteArray(REC_CAPACITY)
    private val recNotes = ByteArray(REC_CAPACITY)
    private val recVels = ByteArray(REC_CAPACITY)
    private var recCount = 0

    /** Snapshot of this run's recording; allocation happens only at run end. */
    fun recordingBytes(): ByteArray? =
        if (recCount == 0) {
            null
        } else {
            com.fingerly.app.data.RecordingCodec.encode(
                recCount, recTimesMs, recTypes, recNotes, recVels,
            )
        }

    /** Free-play lets a tap restart the run; session mode drives flow itself. */
    var tapToRestart = true

    /** Shorter for micro-passages so reps stay tight. */
    var leadInMs = LEAD_IN_MS

    /**
     * Wait mode: the clock freezes when a note reaches the hit line until the
     * right key is pressed. Correctness is graded; timing is not.
     */
    var waitMode = false
    private var waitFrom = 0
    private var waitingNow = false
    private var waitPromptIdx = -1
    private val waitPrompt = StringBuilder(24)

    /**
     * Recall mode (foundations): draw NO answer. The prompt names a key and the
     * learner must produce it — no falling rectangle, no note name on the note,
     * no pre-lit key, no white-key letters (Rowland 2014: recall ≫ recognition).
     */
    var recallMode = false

    /** Show the black-key landmark after this long on one prompt; 0 disables. */
    var revealAfterMs = 0L

    /** Force-miss a prompt after this long so a run can always finish; 0 disables. */
    var forceAdvanceAfterMs = 0L

    /** Per-chart-note prompt text override, e.g. "F" at rung 0 vs "F4" beyond. */
    var promptLabels: Array<String>? = null

    /**
     * Per-prompt render mode (SPEC §4a-F). A [RENDER_STAFF] prompt is drawn as
     * notation and says nothing in words: the staff IS the question. Null means
     * every prompt is a worded one.
     */
    var promptRender: ByteArray? = null

    /** Per-prompt clef for staff prompts; ignored for worded ones. */
    var promptClef: ByteArray? = null

    /**
     * Prompts that SHOW their answer from the instant they arrive — the first
     * time the learner meets a symbol, it is demonstrated rather than tested.
     * Scored as revealed, so a demonstration earns nothing.
     */
    var promptDemo: BooleanArray? = null

    /**
     * Per-prompt scaffold level, 1 = full help, 0 = none (SPEC §4a-F item F2).
     * Drives every aid there is: how soon the answer is revealed, whether the
     * landmark tint appears at all, and whether the middle-C dot is drawn.
     * At zero the screen shows the notation and nothing else, which is what
     * condition 4 of the definition of done requires.
     */
    var promptScaffold: FloatArray? = null

    private fun scaffoldOf(i: Int): Float {
        val a = promptScaffold ?: return 1f
        return if (i in a.indices) a[i] else 1f
    }

    /** Reveal delay for prompt [i]; negative means never reveal. */
    private fun revealDelayFor(i: Int): Long {
        val alpha = scaffoldOf(i)
        if (alpha <= 0f) return -1L
        return (revealAfterMs * (1f - alpha)).toLong()
    }

    /**
     * Cold-read mode (SPEC §4a-F item F3): the excerpt is drawn as notation and
     * NOTHING else is shown — no falling notes, no lit keys, no landmark tint,
     * no reveal. The clock does not wait, because condition 3 of the definition
     * of done requires the read to be played *in time*, not spelled out.
     *
     * The only moving thing is a cursor on the note the judge is waiting for,
     * which is read position rather than a verdict.
     */
    var excerptMode = false
    var excerptMidi: IntArray? = null
    var excerptStartBeats: DoubleArray? = null
    var excerptDurationBeats: DoubleArray? = null
    var excerptClefs: ByteArray? = null
    var excerptTotalBeats = 16.0
    var excerptBeatsPerBar = 4

    /** Built lazily on the first staff prompt so text-only runs never load a font. */
    private var staffRenderer: StaffRenderer? = null

    private fun renderOf(i: Int): Int {
        val r = promptRender ?: return RENDER_TEXT
        return if (i in r.indices) r[i].toInt() else RENDER_TEXT
    }

    private fun clefOf(i: Int): Int {
        val c = promptClef ?: return 0
        return if (i in c.indices) c[i].toInt() else 0
    }

    private fun anyScaffoldLeft(): Boolean {
        val a = promptScaffold ?: return true
        var i = 0
        while (i < a.size) {
            if (a[i] > 0f) return true
            i++
        }
        return false
    }

    /** Suppress the live accuracy/miss/extra/combo HUD (evaluative verdict). */
    var showHud = true

    private var revealedIdx = -1
    private val revealedFlag = BooleanArray(notes.size)

    /**
     * Prompts the APP chose to show the answer to, kept separate from the ones
     * the learner failed to retrieve in time. Both suppress credit, but only one
     * of them is a miss, and reporting a demonstration as a miss makes a perfect
     * first drill read as half wrong.
     */
    private val demonstratedFlag = BooleanArray(notes.size)
    private val twoGroupOffsets = intArrayOf(1, 3)
    private val threeGroupOffsets = intArrayOf(6, 8, 10)

    /** True when prompt [i] was revealed or force-advanced — not unaided. */
    fun wasRevealedAt(i: Int): Boolean = i < revealedFlag.size && revealedFlag[i]

    /** True when prompt [i] was a demonstration: the app showed it, unprompted. */
    fun wasDemonstratedAt(i: Int): Boolean =
        i < demonstratedFlag.size && demonstratedFlag[i]

    private fun promptLabelAt(i: Int): String {
        val labels = promptLabels
        if (labels != null && i < labels.size) return labels[i]
        return noteLabels[notes[i].midiNote]
    }

    // Wait-mode instrumentation for the foundations trainer: how long each
    // prompt took, and which wrong keys were pressed while waiting on it.
    private var clampStartNanos = 0L
    private val waitLatencyMs = IntArray(notes.size)
    private val wrongExpectedIdx = IntArray(WRONG_CAPACITY)
    private val wrongPlayedNote = IntArray(WRONG_CAPACITY)
    private var wrongEventCount = 0

    /** Prompt→correct-key latency in ms, or -1 when never measured. */
    fun waitLatencyMsAt(i: Int): Int = if (i < waitLatencyMs.size) waitLatencyMs[i] else -1
    fun wrongEventCount(): Int = wrongEventCount
    fun wrongExpectedIdxAt(k: Int): Int = wrongExpectedIdx[k]
    fun wrongPlayedNoteAt(k: Int): Int = wrongPlayedNote[k]

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

    /** Per-frame key guidance: 0 none, 1 right-hand note due now, 2 left-hand. */
    private val keyDue = IntArray(128)

    /** Red = wrong key, and nothing else: flash deadline per key (nanos). */
    private val wrongKeyUntil = LongArray(128)

    // Match-by-letter aids: pre-built labels (no per-frame allocation) and the
    // passage's active key range (everything else drawn dimmed).
    private val noteLabels = Array(128) { midi ->
        "${LETTERS[midi % 12]}${midi / 12 - 1}"
    }
    private var rangeLow = 0
    private var rangeHigh = 127

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
    private var lastParticleNanos = 0L
    private var frameIntervalNanos = 8_333_333L
    private var warmupFrames = 0 // skip stats while >0: screen transitions jank
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
    // Missed notes fade to gray — red is reserved for "wrong key pressed".
    private val missNotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 100, 106) }
    private val wrongKeyPaint = Paint().apply { color = Color.rgb(229, 57, 53) }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
    private val dueRightPaint = Paint().apply { color = Color.rgb(0, 145, 75) }
    private val dueLeftPaint = Paint().apply { color = Color.rgb(36, 118, 158) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 100, 110)
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val middleCPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 230, 118) }
    private val landmarkPaint = Paint().apply { color = Color.rgb(120, 90, 30) }
    private val progressBarPaint = Paint().apply { color = Color.rgb(0, 145, 75) }
    private val progressTrackPaint = Paint().apply { color = Color.rgb(30, 38, 44) }
    private val dimWhiteKeyPaint = Paint().apply { color = Color.rgb(150, 152, 150) }
    private val dimBlackKeyPaint = Paint().apply { color = Color.rgb(14, 18, 22) }
    private val keyLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 26, 32)
        textSize = 26f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val noteLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 10, 14)
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
    }
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
                    val evSongMs = (event.timestampNanos - anchorNanos) / 1_000_000 - leadInMs
                    record(MidiEvent.TYPE_NOTE_ON, event.data1, event.data2, evSongMs)
                    val idx = judge.onNoteOn(event.data1, evSongMs)
                    if (idx >= 0) {
                        spawnBurst(noteX[event.data1], keyboardTop, HIT_BURST)
                        if (waitMode && idx < waitLatencyMs.size && clampStartNanos != 0L &&
                            idx == waitFrom
                        ) {
                            waitLatencyMs[idx] =
                                ((event.timestampNanos - clampStartNanos) / 1_000_000)
                                    .toInt().coerceAtLeast(0)
                            clampStartNanos = 0L
                        }
                    } else if (event.data1 < 128) {
                        wrongKeyUntil[event.data1] = event.timestampNanos + WRONG_FLASH_NANOS
                        // Attribute to the CLAMPED prompt, and only while actually
                        // waiting on one: presses during the lead-in or the gap
                        // between prompts used to be blamed on the next prompt.
                        if (waitMode && waitingNow && waitPromptIdx >= 0 &&
                            wrongEventCount < WRONG_CAPACITY
                        ) {
                            wrongExpectedIdx[wrongEventCount] = waitPromptIdx
                            wrongPlayedNote[wrongEventCount] = event.data1
                            wrongEventCount++
                        }
                    }
                }
            }

            MidiEvent.TYPE_NOTE_OFF -> {
                if (event.data1 < 128) pressed[event.data1] = false
                if (!ended) {
                    val evSongMs = (event.timestampNanos - anchorNanos) / 1_000_000 - leadInMs
                    record(MidiEvent.TYPE_NOTE_OFF, event.data1, 0, evSongMs)
                }
            }
        }
    }

    private fun record(type: Int, note: Int, velocity: Int, songMsAt: Long) {
        if (!recordEnabled || recCount >= REC_CAPACITY) return
        recTimesMs[recCount] = songMsAt.toInt()
        recTypes[recCount] = type.toByte()
        recNotes[recCount] = note.toByte()
        recVels[recCount] = velocity.toByte()
        recCount++
    }

    init {
        rebuildHud()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        // Font loading is I/O, so it happens here and never in the frame loop
        // (SPEC §1) — and only when this run actually contains notation.
        if (staffRenderer == null &&
            (excerptMode || promptRender?.any { it.toInt() == RENDER_STAFF } == true)
        ) {
            staffRenderer = StaffRenderer(context)
        }
        restart()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    private val discardHandler = MidiEventHandler { }

    fun restart() {
        // Events queued while no run was active must not be judged as extras.
        ring.drain(discardHandler)
        judge.reset()
        anchorNanos = System.nanoTime()
        songMs = -leadInMs
        ended = false
        drawFrom = 0
        particleCount = 0
        droppedFrames = 0
        warmupFrames = WARMUP_FRAMES
        worstFrameMs = 0f
        lastJudgedTotal = -1
        autoFrom = 0
        recCount = 0
        waitFrom = 0
        clampStartNanos = 0L
        wrongEventCount = 0
        revealedIdx = -1
        java.util.Arrays.fill(revealedFlag, false)
        java.util.Arrays.fill(demonstratedFlag, false)
        java.util.Arrays.fill(waitLatencyMs, -1) // -1 = not measured (0 meant "instant")
        waitingNow = false
        waitPromptIdx = -1
        java.util.Arrays.fill(autoOnSent, false)
        java.util.Arrays.fill(autoOffSent, false)
        java.util.Arrays.fill(pressed, false)
        java.util.Arrays.fill(wrongKeyUntil, 0L)
        rebuildHud()
    }

    /** First chart note still pending (notes are start-sorted). */
    private fun nextPendingStartMs(): Long {
        while (waitFrom < notes.size && judge.stateOf(waitFrom) != HitJudge.STATE_PENDING) {
            waitFrom++
        }
        return if (waitFrom < notes.size) {
            (notes[waitFrom].startSeconds * 1000).toLong()
        } else {
            Long.MAX_VALUE
        }
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

        // Always the full 88 keys (A0–C8): the on-screen keyboard mirrors the
        // physical piano 1:1, so a pressed key lights up in the matching spot
        // and positions transfer directly (SPEC §2.5: exact, verifiable).
        lowNote = 21
        highNote = 108
        rangeLow = notes.minOfOrNull { it.midiNote } ?: 0
        rangeHigh = notes.maxOfOrNull { it.midiNote } ?: 127

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

        // Frame pacing stats (perf gate). The first frames after attach/restart
        // include screen-transition and shader-warmup cost — not steady-state
        // rendering — so they are excluded from the gate numbers.
        if (warmupFrames > 0) {
            warmupFrames--
            lastFrameNanos = 0L
        }
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            val deltaMs = delta / 1_000_000f
            if (deltaMs > worstFrameMs) worstFrameMs = deltaMs
            if (delta > frameIntervalNanos * 3 / 2) droppedFrames++
            val fps = 1_000_000_000f / delta
            emaFps = if (emaFps == 0f) fps else emaFps * 0.95f + fps * 0.05f
        }
        lastFrameNanos = frameTimeNanos

        var raw = (frameTimeNanos - anchorNanos) / 1_000_000 - leadInMs
        waitingNow = false
        if (waitMode && !ended) {
            val target = nextPendingStartMs()
            if (target != Long.MAX_VALUE && raw > target) {
                // Freeze the clock at the note: shift the anchor by the overshoot.
                anchorNanos += (raw - target) * 1_000_000
                raw = target
                waitingNow = true
                if (waitPromptIdx != waitFrom) {
                    waitPromptIdx = waitFrom
                    clampStartNanos = frameTimeNanos
                    waitPrompt.setLength(0)
                    waitPrompt.append("Press ").append(promptLabelAt(waitFrom))
                    // A never-seen symbol is demonstrated, not tested: the answer
                    // is up immediately and the prompt earns nothing.
                    if (promptDemo?.getOrNull(waitFrom) == true) {
                        revealedIdx = waitFrom
                        if (waitFrom < revealedFlag.size) {
                            revealedFlag[waitFrom] = true
                            demonstratedFlag[waitFrom] = true
                        }
                    }
                }
                // Corrective feedback comes AFTER the retrieval attempt: hold the
                // answer back, then show the black-key landmark (Rowland 2014).
                val heldMs = (frameTimeNanos - clampStartNanos) / 1_000_000
                val revealAt = revealDelayFor(waitFrom)
                if (clampStartNanos != 0L && revealAfterMs > 0 && revealAt >= 0 &&
                    heldMs >= revealAt && revealedIdx != waitFrom
                ) {
                    revealedIdx = waitFrom
                    if (waitFrom < revealedFlag.size) revealedFlag[waitFrom] = true
                }
                // Hard escape: a key that cannot be found must never freeze the
                // run forever. Force the miss and move on.
                if (clampStartNanos != 0L && forceAdvanceAfterMs > 0 &&
                    heldMs >= forceAdvanceAfterMs
                ) {
                    if (waitFrom < revealedFlag.size) revealedFlag[waitFrom] = true
                    judge.forceMiss(waitFrom)
                    clampStartNanos = 0L
                    waitPromptIdx = -1
                    revealedIdx = -1
                    waitingNow = false
                }
            }
        }
        songMs = raw
        if (autoplay && !ended) pumpAutoplay()
        ring.drain(drainHandler)
        if (!ended) {
            judge.advanceTo(songMs)
            if (songMs > score.totalSeconds * 1000 + END_GRACE_MS) {
                ended = true
                rebuildEndText()
                logSummary(force = true)
                onEnded?.invoke(judge)
            }
        }

        // Measured delta, not the nominal refresh period: using the nominal
        // value made bursts play in slow motion exactly when frames were being
        // dropped — i.e. when the artifact is most visible. Clamped to 2 frames
        // so a stall cannot teleport particles.
        val dtNanos = if (lastParticleNanos == 0L) {
            frameIntervalNanos
        } else {
            (frameTimeNanos - lastParticleNanos).coerceIn(0L, frameIntervalNanos * 2)
        }
        lastParticleNanos = frameTimeNanos
        updateParticles(dtNanos / 1_000_000_000f)
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
        // A cold read shows the notation and nothing else. Falling notes are the
        // answer drawn at the right x-position, which is exactly what the read
        // is testing the learner to work out.
        if (!excerptMode) drawNotes(canvas)
        drawParticles(canvas)
        drawKeyboard(canvas)
        // Beat pulse: the hit line brightens on every beat so timed reps have a
        // visible rhythm reference (also runs through the lead-in as a count-in).
        val beatMs = (60_000.0 / score.tempoBpm).toLong().coerceAtLeast(1)
        val phase = ((songMs % beatMs) + beatMs) % beatMs
        hitLinePaint.alpha = if (waitingNow) 255 else (255 - phase * 185 / beatMs).toInt()
        canvas.drawLine(0f, keyboardTop, width.toFloat(), keyboardTop, hitLinePaint)
        if (showHud) {
            canvas.drawText(hudLine, 0, hudLine.length, 24f, 48f, hudPaint)
            canvas.drawText(perfLine, 0, perfLine.length, 24f, 96f, hudPaint)
        } else {
            // No live verdict and no streak while drilling: adults with ADHD
            // learn worse under immediate evaluative feedback (Gabay 2018), and a
            // visibly falling accuracy percent is loss-framed (Aster 2024).
            // Instead: a depleting bar showing how much of the drill remains, so
            // the finish state is continuously visible (Plichta & Scheres).
            drawDrillProgress(canvas)
        }
        if (excerptMode) drawExcerpt(canvas)
        if (waitingNow && !excerptMode) {
            if (renderOf(waitPromptIdx) == RENDER_STAFF) {
                drawStaffPrompt(canvas, waitPromptIdx)
            } else {
                canvas.drawText(
                    waitPrompt, 0, waitPrompt.length,
                    width * 0.42f, keyboardTop - 60f, bigPaint,
                )
            }
        }
        if (ended) {
            canvas.drawText(endLine1, 0, endLine1.length, width * 0.30f, height * 0.38f, bigPaint)
            canvas.drawText(endLine2, 0, endLine2.length, width * 0.30f, height * 0.38f + 80f, hudPaint)
        }
    }

    private fun drawNotes(canvas: Canvas) {
        val pps = keyboardTop / (LOOKAHEAD_MS / 1000f) // pixels per second
        val nowSec = songMs / 1000f
        java.util.Arrays.fill(keyDue, 0)
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
            // Key guidance: a pending note at/crossing the hit line lights its key.
            // In recall mode this IS the answer, so it is withheld until the
            // reveal timeout fires for that prompt.
            if (judge.stateOf(i) == HitJudge.STATE_PENDING && startIn <= 0.05f && endIn > 0f &&
                (!recallMode || revealedFlag[i])
            ) {
                keyDue[n.midiNote] = n.hand + 1
                // The landmark tint is help, so it goes when help goes.
                if (recallMode && scaffoldOf(i) > 0f) markLandmarkGroup(n.midiNote)
            }
            var top = keyboardTop - endIn * pps
            var bottom = keyboardTop - startIn * pps
            if (bottom > keyboardTop) bottom = keyboardTop
            if (top < 0f) top = 0f
            // Recall mode draws no falling note for a pending prompt — the
            // rectangle's x-position and printed name are the answer.
            if (recallMode && judge.stateOf(i) == HitJudge.STATE_PENDING && !revealedFlag[i]) {
                i++
                continue
            }
            if (bottom > top) {
                val paint = when (judge.stateOf(i)) {
                    HitJudge.STATE_HIT -> hitNotePaint
                    HitJudge.STATE_MISSED -> missNotePaint
                    else -> if (n.hand == ChartNote.HAND_RIGHT) rightNotePaint else leftNotePaint
                }
                val half = noteW[n.midiNote] / 2f
                noteRect.set(noteX[n.midiNote] - half, top, noteX[n.midiNote] + half, bottom)
                canvas.drawRoundRect(noteRect, 8f, 8f, paint)
                // Note name on the note itself: match by letter, not position.
                if (bottom - top > 44f) {
                    val label = noteLabels[n.midiNote]
                    canvas.drawText(
                        label,
                        noteX[n.midiNote] - noteLabelPaint.measureText(label) / 2f,
                        bottom - 14f,
                        noteLabelPaint,
                    )
                }
            }
            i++
        }
    }

    private fun drawKeyboard(canvas: Canvas) {
        val h = height.toFloat()
        // White keys first; keys outside the passage's range are dimmed so the
        // active zone pops out of the 88-key strip.
        for (n in lowNote..highNote) {
            if (isBlack(n)) continue
            val half = whiteKeyWidth / 2f - 1f
            noteRect.set(noteX[n] - half, keyboardTop + 2f, noteX[n] + half, h)
            canvas.drawRect(noteRect, whiteKeyPaintFor(n))
        }
        // Black keys on top, upper 60% of keyboard height.
        val blackBottom = keyboardTop + (h - keyboardTop) * 0.62f
        for (n in lowNote..highNote) {
            if (!isBlack(n)) continue
            val half = noteW[n] / 2f
            noteRect.set(noteX[n] - half, keyboardTop + 2f, noteX[n] + half, blackBottom)
            canvas.drawRect(noteRect, blackKeyPaintFor(n))
        }
        // Letter on every white key; octave number on Cs; middle C marked.
        // Recall mode keeps ONLY the middle-C dot: printed letters are the answer.
        val labelY = h - 10f
        if (!recallMode) {
            for (n in lowNote..highNote) {
                if (isBlack(n)) continue
                val isC = n % 12 == 0
                val label = if (isC) noteLabels[n] else LETTERS[n % 12]
                val paint = if (n in rangeLow..rangeHigh) keyLetterPaint else labelPaint
                canvas.drawText(label, noteX[n] - paint.measureText(label) / 2f, labelY, paint)
                if (n == 60) canvas.drawCircle(noteX[n], labelY - 34f, 7f, middleCPaint)
            }
        } else if (anyScaffoldLeft()) {
            // The middle-C dot is the last scaffold standing. It goes too, or
            // condition 4 can never be satisfied.
            canvas.drawCircle(noteX[60], labelY - 34f, 7f, middleCPaint)
        }
    }

    /**
     * Light the black-key group that anchors [midi] — the 2-group for C/D/E, the
     * 3-group for F/G/A/B. This is the reveal: it teaches the landmark rule
     * visually instead of restating it as text (SPEC §2.4).
     */
    private fun markLandmarkGroup(midi: Int) {
        val octaveBase = (midi / 12) * 12
        val inTwoGroup = (midi % 12) <= 4 // C, C#, D, D#, E
        // Pre-allocated: this runs inside drawNotes, and any per-frame
        // allocation in the render loop is a bug (SPEC §1).
        val offsets = if (inTwoGroup) twoGroupOffsets else threeGroupOffsets
        for (off in offsets) {
            val n = octaveBase + off
            if (n in lowNote..highNote) keyDue[n] = 3 // landmark tint
        }
    }

    private fun whiteKeyPaintFor(n: Int): Paint = when {
        System.nanoTime() < wrongKeyUntil[n] -> wrongKeyPaint
        pressed[n] -> pressedPaint
        keyDue[n] == 1 -> dueRightPaint
        keyDue[n] == 2 -> dueLeftPaint
        keyDue[n] == 3 -> landmarkPaint
        recallMode -> whiteKeyPaint // no dimming: dimming narrows the answer space
        n !in rangeLow..rangeHigh -> dimWhiteKeyPaint
        else -> whiteKeyPaint
    }

    private fun blackKeyPaintFor(n: Int): Paint = when {
        System.nanoTime() < wrongKeyUntil[n] -> wrongKeyPaint
        pressed[n] -> pressedPaint
        keyDue[n] == 1 -> dueRightPaint
        keyDue[n] == 2 -> dueLeftPaint
        keyDue[n] == 3 -> landmarkPaint
        recallMode -> blackKeyPaint
        n !in rangeLow..rangeHigh -> dimBlackKeyPaint
        else -> blackKeyPaint
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

    /** The cold read: the whole excerpt, with a cursor on the next note due. */
    private fun drawExcerpt(canvas: Canvas) {
        val renderer = staffRenderer ?: return
        val midi = excerptMidi ?: return
        val starts = excerptStartBeats ?: return
        val durs = excerptDurationBeats ?: return
        val clefs = excerptClefs ?: return
        // The cursor is the first note still pending — where the reader is, not
        // how well they are doing.
        var cursor = -1
        var i = 0
        while (i < notes.size) {
            if (judge.stateOf(i) == HitJudge.STATE_PENDING) { cursor = i; break }
            i++
        }
        renderer.drawExcerpt(
            canvas = canvas,
            midi = midi, startBeats = starts, durationBeats = durs, clefs = clefs,
            count = minOf(midi.size, starts.size, durs.size, clefs.size),
            totalBeats = excerptTotalBeats,
            beatsPerBar = excerptBeatsPerBar,
            centerX = width * 0.5f,
            midiCenterY = keyboardTop * 0.46f,
            staffSpace = keyboardTop * 0.040f,
            staffWidth = width * 0.82f,
            cursor = cursor,
        )
    }

    /**
     * The notated prompt. Sized off the playfield rather than fixed pixels so
     * the staff stays the same physical size as the highway on any display.
     */
    private fun drawStaffPrompt(canvas: Canvas, i: Int) {
        val renderer = staffRenderer ?: return
        renderer.draw(
            canvas = canvas,
            midi = notes[i].midiNote,
            clef = clefOf(i),
            centerX = width * 0.5f,
            // Middle C sits at the centre of the grand staff, which spans ten
            // staff spaces — so the space is smaller than a single staff needed.
            midiCenterY = keyboardTop * 0.48f,
            staffSpace = keyboardTop * 0.042f,
            staffWidth = width * 0.32f,
        )
    }

    /** Segmented remaining-prompt bar: position in the drill, no verdict. */
    private fun drawDrillProgress(canvas: Canvas) {
        val total = notes.size
        if (total == 0) return
        val done = judge.judgedCount().coerceAtMost(total)
        val barW = width * 0.5f
        val x0 = (width - barW) / 2f
        val segW = barW / total
        val y = 40f
        for (i in 0 until total) {
            val left = x0 + i * segW + 2f
            noteRect.set(left, y, left + segW - 4f, y + 12f)
            canvas.drawRect(noteRect, if (i < done) progressBarPaint else progressTrackPaint)
        }
    }

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
            // Coverage stated: the average is over hits only, so it must never
            // be readable as "your timing was great" after a run of misses.
            .append("  avg err ").append(judge.avgAbsErrorMs()).append("ms over ")
            .append(judge.hits).append('/').append(judge.noteCount)
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ended && tapToRestart) {
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
        private const val WARMUP_FRAMES = 12 // ~100ms @120Hz
        private const val WRONG_FLASH_NANOS = 450_000_000L
        private const val REC_CAPACITY = 4096
        private const val WRONG_CAPACITY = 256
        private const val RENDER_TEXT = com.fingerly.core.session.FoundationsTrainer.RENDER_TEXT
        private const val RENDER_STAFF = com.fingerly.core.session.FoundationsTrainer.RENDER_STAFF
        private val LETTERS =
            arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }
}
