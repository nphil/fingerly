package com.fingerly.core.notation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout gate for SPEC §4a-F item F3: a known four-bar excerpt is pinned note
 * by note, so a change to either ruler shows up as a failing test rather than
 * as notation that quietly drifts.
 *
 * The excerpt is Ode to Joy's opening in C position — the same material the
 * song path already bundles, which is the point: the cold read is the real
 * task, not a synthetic exercise.
 */
class ExcerptLayoutTest {

    private val E4 = 64
    private val F4 = 65
    private val G4 = 67
    private val D4 = 62
    private val C4 = 60

    /** pitch, start beat, duration beats — four bars of 4/4. */
    private val notes = listOf(
        Triple(E4, 0.0, 1.0), Triple(E4, 1.0, 1.0), Triple(F4, 2.0, 1.0), Triple(G4, 3.0, 1.0),
        Triple(G4, 4.0, 1.0), Triple(F4, 5.0, 1.0), Triple(E4, 6.0, 1.0), Triple(D4, 7.0, 1.0),
        Triple(C4, 8.0, 1.0), Triple(C4, 9.0, 1.0), Triple(D4, 10.0, 1.0), Triple(E4, 11.0, 1.0),
        Triple(E4, 12.0, 1.5), Triple(D4, 13.5, 0.5), Triple(D4, 14.0, 2.0),
    )

    private val totalBeats = 16.0

    @Test
    fun everyNoteLandsOnItsOwnStaffPositionInTreble() {
        // y is pitch: the five distinct pitches occupy five distinct positions,
        // and equal pitches never drift apart.
        val byPitch = HashMap<Int, Int>()
        for ((pitch, _, _) in notes) {
            val ruler = Staff.stepsFromMiddleC(pitch)
            val prior = byPitch.put(pitch, ruler)
            if (prior != null) assertEquals("pitch $pitch moved", prior, ruler)
        }
        assertEquals(5, byPitch.size)
        // C4 is the shared ledger line; E4 is the treble staff's bottom line.
        assertEquals(0, byPitch.getValue(C4))
        assertEquals(2, byPitch.getValue(E4))
        assertEquals(3, byPitch.getValue(F4))
        assertEquals(4, byPitch.getValue(G4))
        assertEquals(1, byPitch.getValue(D4))
        // Only middle C needs a ledger line in this excerpt.
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        assertEquals(1, Staff.ledgerLines(C4, Staff.CLEF_TREBLE, out))
        for (p in listOf(D4, E4, F4, G4)) {
            assertEquals("$p should sit inside the staff", 0, Staff.ledgerLines(p, Staff.CLEF_TREBLE, out))
        }
    }

    @Test
    fun xIsStrictlyIncreasingInTimeAndNeverCollides() {
        var lastX = -1.0
        var lastBeat = -1.0
        for ((_, beat, _) in notes) {
            val x = Staff.xFraction(beat, totalBeats)
            if (beat > lastBeat) {
                assertTrue("beat $beat did not advance x", x > lastX)
                lastX = x
                lastBeat = beat
            }
        }
    }

    @Test
    fun barlinesFallBetweenNotesNotThroughThem() {
        val bars = DoubleArray(Staff.MAX_BARLINES)
        val n = Staff.barlineFractions(totalBeats, 4, bars)
        assertEquals(4, n)
        for (i in 0 until n) {
            for ((_, beat, _) in notes) {
                val x = Staff.xFraction(beat, totalBeats)
                // A barline at beat 4 coincides with the downbeat note by design;
                // every other note must clear it.
                val onDownbeat = (beat % 4.0) == 0.0
                if (!onDownbeat) {
                    assertTrue(
                        "barline $i sits on the note at beat $beat",
                        kotlin.math.abs(x - bars[i]) > 1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun noteheadShapesMatchTheWrittenDurations() {
        val heads = notes.map { Staff.headForBeats(it.third) }
        // Twelve quarters, then the dotted-ish pair, then a half.
        assertEquals(Staff.HEAD_QUARTER, heads[0])
        assertEquals(Staff.HEAD_QUARTER, heads[11])
        assertEquals(Staff.HEAD_QUARTER, heads[13]) // shorter than a beat degrades safely
        assertEquals(Staff.HEAD_HALF, heads.last())
        assertTrue("no whole notes in this excerpt", heads.none { it == Staff.HEAD_WHOLE })
    }

    @Test
    fun stemsAllTurnTheSameWayBelowTheMiddleLine() {
        // Every pitch here is at or below B4, the treble middle line, so every
        // stem points up. A mixed result would mean the turn point moved.
        for ((pitch, _, _) in notes) {
            val fromMiddle = Staff.halfSpaces(pitch, Staff.CLEF_TREBLE) - 4
            assertTrue("$pitch stem should point up", Staff.stemUp(fromMiddle))
        }
    }

    @Test
    fun theWholeExcerptFitsTheSpanTheModuleTrains() {
        val c3 = 48
        val g4 = 67
        for ((pitch, _, _) in notes) {
            assertTrue("$pitch outside C3–G4", pitch in c3..g4)
        }
    }
}
