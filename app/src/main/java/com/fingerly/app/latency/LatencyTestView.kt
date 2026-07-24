package com.fingerly.app.latency

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import com.fingerly.app.log.RemoteLog
import com.fingerly.core.latency.LatencyStats
import com.fingerly.core.midi.MidiEvent
import com.fingerly.core.midi.MidiEventHandler
import com.fingerly.core.midi.MidiEventRing

/**
 * Latency test screen (SPEC §1, debug builds): press any key on the piano; the screen
 * flashes and reports MIDI-receive → render-loop pickup latency, plus an estimated
 * on-screen figure (+1 frame for scanout). Acceptance gate: < 15ms on device.
 *
 * The frame loop is allocation-free: stats text is rebuilt into pre-allocated
 * StringBuilders only when a new keypress arrives, using integer appends only.
 */
@SuppressLint("ViewConstructor")
class LatencyTestView(
    context: Context,
    private val ring: MidiEventRing,
) : View(context), Choreographer.FrameCallback {

    private val stats = LatencyStats(WINDOW)

    private val flashPaint = Paint().apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        textSize = 42f
        typeface = Typeface.MONOSPACE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 130, 140)
        textSize = 42f
        typeface = Typeface.MONOSPACE
    }
    private val failPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 82, 82)
        textSize = 42f
        typeface = Typeface.MONOSPACE
    }

    private val lineLast = StringBuilder(64).append("waiting for keypress…")
    private val lineAvg = StringBuilder(64)
    private val lineOnScreen = StringBuilder(64)
    private val lineGate = StringBuilder(64)
    private val lineMeta = StringBuilder(64)

    private var gatePassed = false
    private var lastLogNanos = 0L
    private var loggedSampleCount = 0L
    private var pendingNoteNanos = 0L
    private var pendingNote = -1
    private var flashUntilNanos = 0L
    private var frameIntervalNanos = 6_944_444L // updated from the real display in doFrame
    private var running = false

    private val drainHandler = MidiEventHandler { event ->
        if (event.type == MidiEvent.TYPE_NOTE_ON) {
            pendingNoteNanos = event.timestampNanos
            pendingNote = event.data1
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val refresh = display?.refreshRate ?: 0f
        if (refresh > 1f) frameIntervalNanos = (1_000_000_000f / refresh).toLong()

        ring.drain(drainHandler)
        if (pendingNoteNanos != 0L) {
            val now = System.nanoTime()
            stats.record(now - pendingNoteNanos)
            flashUntilNanos = now + FLASH_NANOS
            pendingNoteNanos = 0L
            rebuildText(refresh)
        }
        maybeLogSummary(frameTimeNanos, refresh)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * Periodic summary for the remote debug log — at most one line per
     * [LOG_INTERVAL_NANOS], and only when new samples arrived. Never per event:
     * the hot path must stay allocation- and I/O-free (SPEC §1).
     */
    private fun maybeLogSummary(frameTimeNanos: Long, refresh: Float) {
        if (!RemoteLog.isEnabled()) return
        if (lastLogNanos == 0L) lastLogNanos = frameTimeNanos
        if (frameTimeNanos - lastLogNanos < LOG_INTERVAL_NANOS) return
        lastLogNanos = frameTimeNanos
        if (stats.totalCount == loggedSampleCount) return
        loggedSampleCount = stats.totalCount
        val frameMicros = frameIntervalNanos / 1_000
        RemoteLog.log(
            "latency",
            "n=${stats.totalCount} avg=${stats.avgMicros()}us min=${stats.minMicros()}us " +
                "max=${stats.maxMicros()}us onscreen_avg=${stats.avgMicros() + frameMicros}us " +
                "display=${refresh.toInt()}Hz gate=${if (gatePassed) "PASS" else "FAIL"}",
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (System.nanoTime() < flashUntilNanos) {
            canvas.drawRect(0f, 0f, width * 0.25f, height.toFloat(), flashPaint)
        }
        val x = width * 0.30f
        var y = height * 0.25f
        canvas.drawText(lineLast, 0, lineLast.length, x, y, textPaint)
        y += LINE_H
        canvas.drawText(lineAvg, 0, lineAvg.length, x, y, textPaint)
        y += LINE_H
        canvas.drawText(lineOnScreen, 0, lineOnScreen.length, x, y, textPaint)
        y += LINE_H
        canvas.drawText(lineGate, 0, lineGate.length, x, y, if (gatePassed) textPaint else failPaint)
        y += LINE_H * 1.5f
        canvas.drawText(lineMeta, 0, lineMeta.length, x, y, dimPaint)
    }

    /** Only runs when a keypress arrives — never per frame. Integer appends only. */
    private fun rebuildText(refresh: Float) {
        val frameMicros = frameIntervalNanos / 1_000
        val avgOnScreenMicros = stats.avgMicros() + frameMicros

        lineLast.setLength(0)
        lineLast.append("pipeline last ")
        appendMs(lineLast, stats.lastMicros)
        lineLast.append("  note ").append(pendingNote)

        lineAvg.setLength(0)
        lineAvg.append("pipeline avg ")
        appendMs(lineAvg, stats.avgMicros())
        lineAvg.append("  min ")
        appendMs(lineAvg, stats.minMicros())
        lineAvg.append("  max ")
        appendMs(lineAvg, stats.maxMicros())

        lineOnScreen.setLength(0)
        lineOnScreen.append("est. on-screen avg ")
        appendMs(lineOnScreen, avgOnScreenMicros)
        lineOnScreen.append(" (+1 frame scanout)")

        gatePassed = avgOnScreenMicros < GATE_MICROS && stats.windowFill() > 0
        lineGate.setLength(0)
        lineGate.append(if (gatePassed) "GATE <15ms: PASS" else "GATE <15ms: FAIL")

        lineMeta.setLength(0)
        lineMeta.append("samples ").append(stats.totalCount)
            .append("  display ").append(refresh.toInt()).append("Hz")
    }

    companion object {
        private const val WINDOW = 256
        private const val FLASH_NANOS = 90_000_000L
        private const val GATE_MICROS = 15_000L
        private const val LINE_H = 64f
        private const val LOG_INTERVAL_NANOS = 5_000_000_000L

        /** Renders micros as "N.NN ms" without any float formatting (no allocation). */
        private fun appendMs(sb: StringBuilder, micros: Long) {
            sb.append(micros / 1_000).append('.')
            val frac = (micros % 1_000) / 10
            if (frac < 10) sb.append('0')
            sb.append(frac).append(" ms")
        }
    }
}
