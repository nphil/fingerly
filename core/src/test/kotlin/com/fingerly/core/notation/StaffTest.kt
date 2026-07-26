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
}
