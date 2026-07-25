package com.fingerly.core.play

import com.fingerly.core.song.ChartNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitJudgeTest {

    private fun chart(vararg notes: Pair<Int, Double>): List<ChartNote> =
        notes.map { (pitch, start) ->
            ChartNote(pitch, start, 0.5, ChartNote.HAND_RIGHT, 1)
        }

    @Test
    fun perfectPlayHitsEverything() {
        val judge = HitJudge(chart(60 to 0.0, 62 to 1.0, 64 to 2.0))
        assertTrue(judge.onNoteOn(60, 0) >= 0)
        assertTrue(judge.onNoteOn(62, 1000) >= 0)
        assertTrue(judge.onNoteOn(64, 2000) >= 0)
        judge.advanceTo(3000)
        assertEquals(3, judge.hits)
        assertEquals(0, judge.misses)
        assertEquals(0, judge.extras)
        assertEquals(100f, judge.accuracyPercent())
        assertEquals(3, judge.combo)
    }

    @Test
    fun earlyAndLateWithinWindowHit() {
        val judge = HitJudge(chart(60 to 1.0), hitWindowMs = 150)
        assertTrue(judge.onNoteOn(60, 860) >= 0) // 140ms early
        val judge2 = HitJudge(chart(60 to 1.0), hitWindowMs = 150)
        assertTrue(judge2.onNoteOn(60, 1140) >= 0) // 140ms late
    }

    @Test
    fun outsideWindowIsExtra() {
        val judge = HitJudge(chart(60 to 1.0), hitWindowMs = 150)
        assertEquals(-1, judge.onNoteOn(60, 700)) // 300ms early
        assertEquals(1, judge.extras)
        assertEquals(0, judge.hits)
    }

    @Test
    fun wrongPitchIsExtraAndBreaksCombo() {
        val judge = HitJudge(chart(60 to 0.0, 62 to 1.0))
        judge.onNoteOn(60, 0)
        assertEquals(1, judge.combo)
        assertEquals(-1, judge.onNoteOn(61, 1000))
        assertEquals(0, judge.combo)
        assertEquals(1, judge.extras)
    }

    @Test
    fun unplayedNoteBecomesMissAfterWindow() {
        val judge = HitJudge(chart(60 to 1.0), missAfterMs = 300)
        judge.advanceTo(1200)
        assertEquals(0, judge.misses) // still within grace
        judge.advanceTo(1301)
        assertEquals(1, judge.misses)
        // Too late to hit now.
        assertEquals(-1, judge.onNoteOn(60, 1350))
    }

    @Test
    fun chordNotesJudgedIndependently() {
        val judge = HitJudge(chart(60 to 1.0, 64 to 1.0, 67 to 1.0))
        assertTrue(judge.onNoteOn(64, 1010) >= 0)
        assertTrue(judge.onNoteOn(60, 1020) >= 0)
        judge.advanceTo(2000)
        assertEquals(2, judge.hits)
        assertEquals(1, judge.misses) // 67 never played
    }

    @Test
    fun doubleHitSameNoteCountsExtra() {
        val judge = HitJudge(chart(60 to 1.0))
        assertTrue(judge.onNoteOn(60, 1000) >= 0)
        assertEquals(-1, judge.onNoteOn(60, 1050)) // note already consumed
        assertEquals(1, judge.hits)
        assertEquals(1, judge.extras)
    }

    @Test
    fun repeatedPitchMatchesNearest() {
        val judge = HitJudge(chart(60 to 1.0, 60 to 1.2), hitWindowMs = 150)
        val idx = judge.onNoteOn(60, 1190)
        assertEquals(1, idx) // nearest is the 1.2s note
        assertTrue(judge.onNoteOn(60, 1000) >= 0) // first still hittable
    }

    @Test
    fun timingErrorTracked() {
        val judge = HitJudge(chart(60 to 1.0, 62 to 2.0))
        val a = judge.onNoteOn(60, 1100) // 100ms late
        val b = judge.onNoteOn(62, 1950) // 50ms early
        assertEquals(100L, judge.errorMsOf(a))
        assertEquals(-50L, judge.errorMsOf(b))
        assertEquals(75L, judge.avgAbsErrorMs())
    }

    @Test
    fun accuracyCountsExtrasAgainst() {
        val judge = HitJudge(chart(60 to 0.0, 62 to 1.0))
        judge.onNoteOn(60, 0)
        judge.onNoteOn(65, 500) // extra
        judge.advanceTo(2000) // 62 missed
        assertEquals(33.333f, judge.accuracyPercent(), 0.01f)
    }

    @Test
    fun anyOctaveModeAcceptsTheRightLetterAnywhere() {
        // Foundations home rung: the skill is "which white key is a G", so any
        // G must count — the learner has not been taught octave numbers yet.
        val judge = HitJudge(
            chart(67 to 1.0), // G4
            matchAnyOctave = booleanArrayOf(true),
        )
        assertTrue(judge.onNoteOn(55, 1000) >= 0) // G3
        assertEquals(1, judge.hits)
        assertEquals(0, judge.extras)

        val wrongLetter = HitJudge(chart(67 to 1.0), matchAnyOctave = booleanArrayOf(true))
        assertEquals(-1, wrongLetter.onNoteOn(65, 1000)) // F is still wrong
        assertEquals(1, wrongLetter.extras)
    }

    @Test
    fun exactMatchingRemainsTheDefault() {
        val judge = HitJudge(chart(67 to 1.0))
        assertEquals(-1, judge.onNoteOn(55, 1000)) // wrong octave = wrong note
        assertEquals(1, judge.extras)
    }

    @Test
    fun forceMissUnblocksAStuckPrompt() {
        val judge = HitJudge(chart(60 to 0.0, 62 to 1.0))
        judge.forceMiss(0)
        assertEquals(1, judge.misses)
        assertEquals(HitJudge.STATE_MISSED, judge.stateOf(0))
        judge.forceMiss(0) // idempotent
        assertEquals(1, judge.misses)
        // The next note is still hittable.
        assertTrue(judge.onNoteOn(62, 1000) >= 0)
    }

    @Test
    fun resetClearsEverything() {
        val judge = HitJudge(chart(60 to 0.0))
        judge.onNoteOn(60, 0)
        judge.reset()
        assertEquals(0, judge.hits)
        assertEquals(HitJudge.STATE_PENDING, judge.stateOf(0))
        assertTrue(judge.onNoteOn(60, 0) >= 0)
    }
}
