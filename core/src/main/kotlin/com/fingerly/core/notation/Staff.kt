package com.fingerly.core.notation

/**
 * Staff geometry: MIDI note → vertical position on a five-line staff.
 *
 * Pure math, no Android — the render layer ([com.fingerly.app.highway.StaffRenderer])
 * only turns these numbers into pixels, so every placement rule is unit-tested
 * on the JVM.
 *
 * Positions are counted in HALF STAFF SPACES above the bottom staff line, which
 * is the natural unit: one diatonic step (C→D→E…) is exactly one half space, so
 * even values are lines and odd values are spaces. The bottom line is 0 and the
 * top line is 8.
 *
 * Notation is diatonic, not chromatic: C♯ and C occupy the SAME staff position
 * and differ only by an accidental. So position is computed from the letter, not
 * from the semitone.
 */
object Staff {

    const val CLEF_TREBLE = 0
    const val CLEF_BASS = 1

    /** Either clef is valid for this prompt; the drill picks one per prompt. */
    const val CLEF_EITHER = 2

    /** Lines are at even half-spaces 0..8; the staff has five of them. */
    const val LINES = 5
    const val TOP_HALF_SPACE = 8

    /** Diatonic degree of each pitch class; black keys take the letter below. */
    private val DEGREE = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)

    /** True when the pitch class needs a sharp to be spelled from [DEGREE]. */
    private val SHARPED = booleanArrayOf(
        false, true, false, true, false, false, true, false, true, false, true, false,
    )

    /**
     * Absolute diatonic index: C-1 = 0 and every letter step adds one.
     * MIDI 60 (C4) → 4*7 + 0 = 28.
     */
    fun diatonicStep(midi: Int): Int = (midi / 12 - 1) * 7 + DEGREE[midi % 12]

    fun needsSharp(midi: Int): Boolean = SHARPED[midi % 12]

    /** Diatonic index of the bottom staff line: E4 in treble, G2 in bass. */
    fun bottomLineStep(clef: Int): Int = if (clef == CLEF_BASS) {
        diatonicStep(43) // G2
    } else {
        diatonicStep(64) // E4
    }

    /**
     * Half staff spaces above the bottom line. 0 = bottom line, 8 = top line,
     * negative = below the staff. Middle C is −2 in treble and +10 in bass —
     * the same ledger line seen from either side, which is the whole reason it
     * is the anchor landmark.
     */
    fun halfSpaces(midi: Int, clef: Int): Int = diatonicStep(midi) - bottomLineStep(clef)

    fun isOnLine(midi: Int, clef: Int): Boolean = halfSpaces(midi, clef) % 2 == 0

    // ------------------------------------------------------------- grand staff
    //
    // Both staves share ONE continuous diatonic ruler, which is the only way the
    // middle-C landmark is true rather than asserted: C4 lands exactly halfway
    // between the bass staff's top line and the treble staff's bottom line, so
    // "the line between the two staves" is something the learner can see.

    /** C4. Every grand-staff position is measured from here. */
    val MIDDLE_C_STEP = diatonicStep(60)

    /** Signed diatonic distance from middle C; +1 per letter upward. */
    fun stepsFromMiddleC(midi: Int): Int = diatonicStep(midi) - MIDDLE_C_STEP

    /** Ruler positions of the five treble lines, low to high (E4…F5). */
    val TREBLE_LINE_STEPS =
        IntArray(LINES) { diatonicStep(64) - MIDDLE_C_STEP + it * 2 }

    /** Ruler positions of the five bass lines, low to high (G2…A3). */
    val BASS_LINE_STEPS =
        IntArray(LINES) { diatonicStep(43) - MIDDLE_C_STEP + it * 2 }

    /**
     * Ruler position of a note, and of anything measured in that clef's half
     * spaces — ledger lines and clef anchors included.
     */
    fun rulerOfHalfSpaces(halfSpaces: Int, clef: Int): Int =
        bottomLineStep(clef) - MIDDLE_C_STEP + halfSpaces

    /**
     * Ledger lines needed for [midi], written into [out] as half-space values,
     * returning how many were written. Allocation-free: the renderer owns [out].
     *
     * A note below the staff gets a line at every even position from −2 down to
     * it; above the staff, from +10 up to it. A note in a space between two
     * ledger lines still needs the line above/below it, which falls out of the
     * even-step walk.
     */
    fun ledgerLines(midi: Int, clef: Int, out: IntArray): Int {
        val h = halfSpaces(midi, clef)
        var n = 0
        if (h <= -2) {
            var v = -2
            while (v >= h && n < out.size) {
                out[n++] = v
                v -= 2
            }
        } else if (h >= TOP_HALF_SPACE + 2) {
            var v = TOP_HALF_SPACE + 2
            while (v <= h && n < out.size) {
                out[n++] = v
                v += 2
            }
        }
        return n
    }

    /**
     * Half-space position of a clef glyph's baseline. SMuFL anchors a clef to
     * the line it names: the G clef curls around the G line (2), the F clef's
     * two dots straddle the F line (6).
     */
    fun clefAnchorHalfSpaces(clef: Int): Int = if (clef == CLEF_BASS) 6 else 2

    /** Ledger lines never exceed this for the C3–G4 span the module trains. */
    const val MAX_LEDGER_LINES = 6

    // ------------------------------------------------------- horizontal ruler
    //
    // Notation is a chart (SPEC §4): y is pitch, x is time. The vertical ruler
    // above is diatonic; this one is metric. Both are pure so placement is
    // unit-tested rather than nudged until it looks right.

    /** Note head shapes. Duration is read off the shape, not printed anywhere. */
    const val HEAD_WHOLE = 0
    const val HEAD_HALF = 1
    const val HEAD_QUARTER = 2

    /**
     * Which notehead a duration draws. Beats are quarter-notes. The module's
     * excerpts are quarter/half/whole only — no beams, no dots, no tuplets —
     * so anything shorter than a beat is rendered as a quarter rather than
     * silently mis-drawn.
     */
    fun headForBeats(beats: Double): Int = when {
        beats >= 3.5 -> HEAD_WHOLE
        beats >= 1.75 -> HEAD_HALF
        else -> HEAD_QUARTER
    }

    /** A stem points down above the middle line, up below it. */
    fun stemUp(halfSpacesFromMiddleLine: Int): Boolean = halfSpacesFromMiddleLine < 0

    /**
     * Fraction of the excerpt's width at which a note at [beat] is drawn.
     * [totalBeats] is the excerpt's full length. Notes are laid out
     * proportionally to time — the chart reading SPEC §4 asks for — with a
     * left inset for the clef and a right margin so the last note is not
     * flush against the final barline.
     */
    fun xFraction(beat: Double, totalBeats: Double): Double {
        if (totalBeats <= 0.0) return LEFT_INSET
        val t = (beat / totalBeats).coerceIn(0.0, 1.0)
        return LEFT_INSET + t * (1.0 - LEFT_INSET - RIGHT_MARGIN)
    }

    /** Barline positions as width fractions, including the closing line. */
    fun barlineFractions(totalBeats: Double, beatsPerBar: Int, out: DoubleArray): Int {
        if (beatsPerBar <= 0 || totalBeats <= 0.0) return 0
        var n = 0
        var beat = beatsPerBar.toDouble()
        while (beat <= totalBeats + 1e-9 && n < out.size) {
            out[n++] = xFraction(beat, totalBeats)
            beat += beatsPerBar
        }
        return n
    }

    /** Room for the clef; notation starts after it, never under it. */
    const val LEFT_INSET = 0.14

    /** Breathing room before the closing barline. */
    const val RIGHT_MARGIN = 0.06

    /** Four bars of 4/4 is 16 barline slots' worth of headroom. */
    const val MAX_BARLINES = 16
}
