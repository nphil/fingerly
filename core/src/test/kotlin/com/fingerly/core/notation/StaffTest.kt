package com.fingerly.core.notation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffTest {

    private fun midi(name: String): Int {
        val letter = name[0]
        val octave = name.substring(1).toInt()
        val semis = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        return (octave + 1) * 12 + semis.getValue(letter)
    }

    @Test
    fun trebleLinesAreEGBDF() {
        val expected = listOf("E4" to 0, "G4" to 2, "B4" to 4, "D5" to 6, "F5" to 8)
        for ((name, h) in expected) {
            assertEquals(name, h, Staff.halfSpaces(midi(name), Staff.CLEF_TREBLE))
            assertTrue("$name must sit on a line", Staff.isOnLine(midi(name), Staff.CLEF_TREBLE))
        }
    }

    @Test
    fun bassLinesAreGBDFA() {
        val expected = listOf("G2" to 0, "B2" to 2, "D3" to 4, "F3" to 6, "A3" to 8)
        for ((name, h) in expected) {
            assertEquals(name, h, Staff.halfSpaces(midi(name), Staff.CLEF_BASS))
            assertTrue("$name must sit on a line", Staff.isOnLine(midi(name), Staff.CLEF_BASS))
        }
    }

    @Test
    fun middleCIsTheSameLedgerLineSeenFromEitherClef() {
        val c4 = midi("C4")
        // One ledger line below the treble staff…
        assertEquals(-2, Staff.halfSpaces(c4, Staff.CLEF_TREBLE))
        // …and one above the bass staff. That shared line is the anchor landmark.
        assertEquals(10, Staff.halfSpaces(c4, Staff.CLEF_BASS))

        val out = IntArray(Staff.MAX_LEDGER_LINES)
        assertEquals(1, Staff.ledgerLines(c4, Staff.CLEF_TREBLE, out))
        assertEquals(-2, out[0])
        assertEquals(1, Staff.ledgerLines(c4, Staff.CLEF_BASS, out))
        assertEquals(10, out[0])
    }

    @Test
    fun theThreeLandmarkAtomsLandWhereTheClefsPointAtThem() {
        // The treble clef curls around its G line; the bass clef's dots straddle F.
        assertEquals(
            Staff.halfSpaces(midi("G4"), Staff.CLEF_TREBLE),
            Staff.clefAnchorHalfSpaces(Staff.CLEF_TREBLE),
        )
        assertEquals(
            Staff.halfSpaces(midi("F3"), Staff.CLEF_BASS),
            Staff.clefAnchorHalfSpaces(Staff.CLEF_BASS),
        )
    }

    @Test
    fun notesInsideTheStaffNeedNoLedgerLines() {
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        for (m in midi("E4")..midi("F5")) {
            assertEquals(0, Staff.ledgerLines(m, Staff.CLEF_TREBLE, out))
        }
    }

    @Test
    fun aNoteInASpaceStillGetsTheLedgerLineAboveIt() {
        // B3 sits in the space just below middle C's ledger line: still one line.
        val b3 = midi("B3")
        assertEquals(-3, Staff.halfSpaces(b3, Staff.CLEF_TREBLE))
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        assertEquals(1, Staff.ledgerLines(b3, Staff.CLEF_TREBLE, out))
        assertEquals(-2, out[0])
    }

    @Test
    fun ledgerLinesAccumulateGoingFurtherOut() {
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        // A3 is two ledger lines below the treble staff (C4 and A3 itself).
        assertEquals(2, Staff.ledgerLines(midi("A3"), Staff.CLEF_TREBLE, out))
        assertEquals(-2, out[0])
        assertEquals(-4, out[1])
        // C3 is INSIDE the bass staff (second space up) — no ledger line.
        assertEquals(3, Staff.halfSpaces(midi("C3"), Staff.CLEF_BASS))
        assertEquals(0, Staff.ledgerLines(midi("C3"), Staff.CLEF_BASS, out))
        // E2 is the first note that needs one below the bass staff.
        assertEquals(1, Staff.ledgerLines(midi("E2"), Staff.CLEF_BASS, out))
        assertEquals(-2, out[0])
    }

    @Test
    fun sharpsShareTheStaffPositionOfTheLetterBelow() {
        // Notation is diatonic: F♯4 and F4 occupy the same line.
        val f4 = midi("F4")
        assertEquals(
            Staff.halfSpaces(f4, Staff.CLEF_TREBLE),
            Staff.halfSpaces(f4 + 1, Staff.CLEF_TREBLE),
        )
        assertFalse(Staff.needsSharp(f4))
        assertTrue(Staff.needsSharp(f4 + 1))
    }

    @Test
    fun theModulesSpanNeverExceedsTheLedgerBudget() {
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        for (m in midi("C3")..midi("G4")) {
            val clef = if (m < 60) Staff.CLEF_BASS else Staff.CLEF_TREBLE
            val n = Staff.ledgerLines(m, clef, out)
            assertTrue("$m needed $n ledger lines", n <= Staff.MAX_LEDGER_LINES)
        }
    }

    @Test
    fun middleCLandsExactlyBetweenTheTwoStavesOnTheGrandStaff() {
        // The tip says "the line BETWEEN the two staves". This is what makes it
        // true instead of a claim the learner has to take on faith.
        assertEquals(0, Staff.stepsFromMiddleC(midi("C4")))
        val bassTop = Staff.BASS_LINE_STEPS.last()   // A3
        val trebleBottom = Staff.TREBLE_LINE_STEPS.first() // E4
        assertEquals(-2, bassTop)
        assertEquals(2, trebleBottom)
        assertEquals("C4 must be the midpoint", 0, (bassTop + trebleBottom) / 2)
    }

    @Test
    fun clefAnchorsMapOntoTheGrandStaffRuler() {
        assertEquals(
            Staff.stepsFromMiddleC(midi("G4")),
            Staff.rulerOfHalfSpaces(
                Staff.clefAnchorHalfSpaces(Staff.CLEF_TREBLE), Staff.CLEF_TREBLE,
            ),
        )
        assertEquals(
            Staff.stepsFromMiddleC(midi("F3")),
            Staff.rulerOfHalfSpaces(
                Staff.clefAnchorHalfSpaces(Staff.CLEF_BASS), Staff.CLEF_BASS,
            ),
        )
    }

    @Test
    fun bothStavesShareOneRulerSoTheStaffLinesNeverCollide() {
        val all = Staff.BASS_LINE_STEPS.toList() + Staff.TREBLE_LINE_STEPS.toList()
        assertEquals("no line may sit on another", all.size, all.toSet().size)
        assertEquals(all.sorted(), all)
        // The gap between the staves is exactly one C4 ledger line wide.
        assertEquals(4, Staff.TREBLE_LINE_STEPS.first() - Staff.BASS_LINE_STEPS.last())
    }

    @Test
    fun aLedgerLineMapsToTheSameRulerPositionFromEitherClef() {
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        val c4 = midi("C4")
        Staff.ledgerLines(c4, Staff.CLEF_TREBLE, out)
        val fromTreble = Staff.rulerOfHalfSpaces(out[0], Staff.CLEF_TREBLE)
        Staff.ledgerLines(c4, Staff.CLEF_BASS, out)
        val fromBass = Staff.rulerOfHalfSpaces(out[0], Staff.CLEF_BASS)
        assertEquals("it is ONE line, drawn in one place", fromTreble, fromBass)
        assertEquals(0, fromTreble)
    }
    // ------------------------------------------------- horizontal ruler (F3)

    @Test
    fun timeIsLaidOutProportionallyBetweenClefAndClosingBarline() {
        val total = 16.0 // four bars of 4/4
        assertEquals(Staff.LEFT_INSET, Staff.xFraction(0.0, total), 1e-9)
        assertEquals(
            1.0 - Staff.RIGHT_MARGIN,
            Staff.xFraction(total, total),
            1e-9,
        )
        // Halfway through the excerpt is halfway across the usable width.
        val mid = Staff.xFraction(total / 2, total)
        assertEquals(
            (Staff.LEFT_INSET + (1.0 - Staff.RIGHT_MARGIN)) / 2, mid, 1e-9,
        )
        // Nothing is ever drawn under the clef.
        for (b in 0..16) {
            val x = Staff.xFraction(b.toDouble(), total)
            assertTrue("beat $b left of the clef", x >= Staff.LEFT_INSET - 1e-9)
            assertTrue("beat $b past the barline", x <= 1.0 - Staff.RIGHT_MARGIN + 1e-9)
        }
    }

    @Test
    fun equalBeatsAreEquallySpaced() {
        val total = 16.0
        val step = Staff.xFraction(1.0, total) - Staff.xFraction(0.0, total)
        for (b in 1..15) {
            val d = Staff.xFraction(b + 1.0, total) - Staff.xFraction(b.toDouble(), total)
            assertEquals("beat $b", step, d, 1e-9)
        }
    }

    @Test
    fun fourBarsOfFourFourDrawFourBarlinesEndingAtTheClosingLine() {
        val out = DoubleArray(Staff.MAX_BARLINES)
        val n = Staff.barlineFractions(16.0, 4, out)
        assertEquals(4, n)
        assertEquals(Staff.xFraction(4.0, 16.0), out[0], 1e-9)
        assertEquals(Staff.xFraction(16.0, 16.0), out[3], 1e-9)
        // The last barline is the closing one, at the right margin.
        assertEquals(1.0 - Staff.RIGHT_MARGIN, out[3], 1e-9)
    }

    @Test
    fun barlineWalkIsBoundedAndDegradesSafely() {
        val out = DoubleArray(Staff.MAX_BARLINES)
        assertEquals(0, Staff.barlineFractions(16.0, 0, out))
        assertEquals(0, Staff.barlineFractions(0.0, 4, out))
        // A pathological excerpt cannot overrun the caller's buffer.
        val tiny = DoubleArray(2)
        assertEquals(2, Staff.barlineFractions(400.0, 1, tiny))
    }

    @Test
    fun noteheadShapeComesFromDurationNotFromText() {
        assertEquals(Staff.HEAD_QUARTER, Staff.headForBeats(1.0))
        assertEquals(Staff.HEAD_HALF, Staff.headForBeats(2.0))
        assertEquals(Staff.HEAD_WHOLE, Staff.headForBeats(4.0))
        // Anything shorter than a beat draws as a quarter rather than silently
        // becoming a shape the module never teaches.
        assertEquals(Staff.HEAD_QUARTER, Staff.headForBeats(0.5))
        assertEquals(Staff.HEAD_QUARTER, Staff.headForBeats(0.25))
    }

    @Test
    fun stemsTurnAtTheMiddleLine() {
        assertTrue(Staff.stemUp(-1))   // below the middle line: stem up
        assertFalse(Staff.stemUp(0))   // on it: stem down, by convention
        assertFalse(Staff.stemUp(3))   // above it: stem down
    }
}
