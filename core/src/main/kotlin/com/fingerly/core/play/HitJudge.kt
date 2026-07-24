package com.fingerly.core.play

import com.fingerly.core.song.ChartNote

/**
 * Live hit/miss detection against a chart (SPEC §7 Phase 2). All state lives in
 * pre-allocated parallel arrays — zero allocation after construction, safe to
 * call from the render loop (SPEC §1).
 *
 * Rules:
 *  - a note-on within ±[hitWindowMs] of a pending chart note of the same pitch
 *    is a HIT (nearest such note wins);
 *  - a chart note not hit by start + [missAfterMs] is a MISS;
 *  - a note-on matching nothing is an EXTRA (wrong note).
 *
 * Feedback is diagnostic, not motivational (SPEC §2.8): counts and timing
 * error, no vibes.
 */
class HitJudge(
    notes: List<ChartNote>,
    private val hitWindowMs: Long = 150,
    private val missAfterMs: Long = 300,
) {

    val noteCount = notes.size
    private val startMs = LongArray(noteCount)
    private val pitch = IntArray(noteCount)
    private val state = IntArray(noteCount) // PENDING / HIT / MISSED
    private val errMs = LongArray(noteCount) // signed timing error for hits (+ = late)

    private var scanFrom = 0 // first index that can still change state

    var hits = 0; private set
    var misses = 0; private set
    var extras = 0; private set
    var combo = 0; private set
    var bestCombo = 0; private set
    private var sumAbsErrMs = 0L

    init {
        // Chart is pre-sorted by start; copy into flat arrays.
        for (i in notes.indices) {
            startMs[i] = (notes[i].startSeconds * 1000).toLong()
            pitch[i] = notes[i].midiNote
        }
    }

    fun reset() {
        state.fill(STATE_PENDING)
        scanFrom = 0
        hits = 0; misses = 0; extras = 0; combo = 0; bestCombo = 0
        sumAbsErrMs = 0
    }

    /** State of chart note [i]: [STATE_PENDING], [STATE_HIT] or [STATE_MISSED]. */
    fun stateOf(i: Int): Int = state[i]

    /** Judge a played note-on at song time [songMs]. Returns hit index or -1 (extra). */
    fun onNoteOn(midiNote: Int, songMs: Long): Int {
        var best = -1
        var bestAbs = Long.MAX_VALUE
        var i = scanFrom
        while (i < noteCount && startMs[i] <= songMs + hitWindowMs) {
            if (state[i] == STATE_PENDING && pitch[i] == midiNote) {
                val abs = if (startMs[i] > songMs) startMs[i] - songMs else songMs - startMs[i]
                if (abs <= hitWindowMs && abs < bestAbs) {
                    bestAbs = abs
                    best = i
                }
            }
            i++
        }
        if (best < 0) {
            extras++
            combo = 0
            return -1
        }
        state[best] = STATE_HIT
        errMs[best] = songMs - startMs[best]
        sumAbsErrMs += bestAbs
        hits++
        combo++
        if (combo > bestCombo) bestCombo = combo
        return best
    }

    /** Mark misses up to song time [songMs]. Call once per frame. */
    fun advanceTo(songMs: Long) {
        var i = scanFrom
        while (i < noteCount && startMs[i] + missAfterMs < songMs) {
            if (state[i] == STATE_PENDING) {
                state[i] = STATE_MISSED
                misses++
                combo = 0
            }
            if (i == scanFrom && state[i] != STATE_PENDING) scanFrom++
            i++
        }
    }

    /** 0–100. Extras count against accuracy: wrong notes are not free. */
    fun accuracyPercent(): Float {
        val total = hits + misses + extras
        if (total == 0) return 100f
        return hits * 100f / total
    }

    /** Mean absolute timing error of hits, in ms. */
    fun avgAbsErrorMs(): Long = if (hits == 0) 0 else sumAbsErrMs / hits

    /** Signed timing error of hit note [i] (+ = late). Valid only when hit. */
    fun errorMsOf(i: Int): Long = errMs[i]

    fun judgedCount(): Int = hits + misses

    companion object {
        const val STATE_PENDING = 0
        const val STATE_HIT = 1
        const val STATE_MISSED = 2
    }
}
