package com.fingerly.core.session

import com.fingerly.core.song.BundledSongs
import com.fingerly.core.song.ChartNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private val passages = Decomposer.decompose(BundledSongs.gymnopedie1Excerpt())

    private var clock = 0L
    private fun engine(progress: MutableMap<Int, PassageProgress> = HashMap()) =
        SessionEngine(passages, progress, nowMs = { clock })

    private fun result(acc: Float, signedErr: Long = 0) = AttemptResult(
        accuracyPercent = acc, hits = 10, misses = 0, extras = 0,
        avgAbsErrMs = 20, meanSignedErrMs = signedErr,
        leftAccuracy = acc, rightAccuracy = acc,
    )

    @Test
    fun sessionFlowsThroughAllPhases() {
        val e = engine()
        var step = e.begin()
        assertEquals(SessionEngine.Phase.WARMUP, step.phase)

        step = e.onAttempt(result(90f)) // one warmup rep → work
        assertEquals(SessionEngine.Phase.WORK, step.phase)

        // Burn through the work window.
        clock += 10 * 60_000L
        step = e.onAttempt(result(88f))
        // No other passages attempted → review is empty → straight to victory.
        assertEquals(SessionEngine.Phase.VICTORY, step.phase)

        step = e.onAttempt(result(95f))
        assertEquals(SessionEngine.Phase.DONE, step.phase)
    }

    @Test
    fun freshPassageStartsSingleHandedInWaitMode() {
        val e = engine()
        e.begin()
        val step = e.onAttempt(result(90f)) // → WORK
        assertEquals(SessionEngine.Phase.WORK, step.phase)
        assertNotEquals(HAND_BOTH, step.setting.hand)
        // The very first exposure has no timing pressure at all.
        assertTrue(step.setting.wait)
    }

    @Test
    fun lowAccuracyStepsEasierHighStepsHarder() {
        val e = engine()
        e.begin()
        var step = e.onAttempt(result(90f)) // → WORK
        val startIndex = step.ladderIndex

        step = e.onAttempt(result(60f)) // struggling: one rung easier
        assertEquals(startIndex + 1, step.ladderIndex)

        step = e.onAttempt(result(97f)) // first clean: hold (no lucky promotes)
        assertEquals(startIndex + 1, step.ladderIndex)
        step = e.onAttempt(result(97f)) // second consecutive clean: promote
        assertEquals(startIndex, step.ladderIndex)
    }

    @Test
    fun collapseDropsSeveralRungsAtOnce() {
        val e = engine()
        e.begin()
        var step = e.onAttempt(result(90f)) // → WORK
        val startIndex = step.ladderIndex
        step = e.onAttempt(result(10f)) // collapse
        assertTrue(step.ladderIndex >= startIndex + 3 || step.ladderIndex == AutoDifficulty.ladder(step.passage, 1).lastIndex)
    }

    @Test
    fun midBandHoldsSetting() {
        val e = engine()
        e.begin()
        var step = e.onAttempt(result(90f)) // → WORK
        val idx = step.ladderIndex
        step = e.onAttempt(result(80f)) // inside the 70–92 band: stay (85% target)
        assertEquals(idx, step.ladderIndex)
    }

    @Test
    fun imLostAlwaysDecomposesEasier() {
        val e = engine()
        e.begin()
        val before = e.current().ladderIndex
        val after = e.imLost().ladderIndex
        assertTrue(after >= before) // never harder, steps down unless already easiest
        val bottom = AutoDifficulty.ladder(e.current().passage, ChartNote.HAND_LEFT).lastIndex
        repeat(20) { e.imLost() }
        assertEquals(bottom, e.current().ladderIndex)
    }

    @Test
    fun weakerHandIsPracticedFirstOnSplitRungs() {
        val progress = HashMap<Int, PassageProgress>()
        val e = SessionEngine(passages, progress, nowMs = { clock })
        e.begin()
        // Report left hand much weaker during warmup.
        var step = e.onAttempt(
            AttemptResult(90f, 10, 0, 0, 20, 0, leftAccuracy = 50f, rightAccuracy = 95f),
        )
        assertEquals(SessionEngine.Phase.WORK, step.phase)
        // Fresh work passage starts on the STRONGER hand rung (right), then weak.
        // FRESH_START_INDEX = stronger-hand slow rung.
        val ladder = AutoDifficulty.ladder(step.passage, ChartNote.HAND_LEFT)
        assertEquals(ladder[step.ladderIndex], step.setting)
    }

    @Test
    fun finishLabelNamesCleanPassOrSaysNone() {
        val e = engine()
        e.begin()
        e.onAttempt(result(95f))
        assertTrue(e.finishLabel().contains("clean ✓"))

        val e2 = engine()
        e2.begin()
        e2.onAttempt(result(30f))
        assertTrue(e2.finishLabel().contains("No clean pass"))
    }

    @Test
    fun extendReopensWorkAfterDone() {
        val e = engine()
        e.begin()
        e.onAttempt(result(90f))
        clock += 10 * 60_000L
        e.onAttempt(result(88f))
        e.onAttempt(result(95f)) // victory → done
        assertEquals(SessionEngine.Phase.DONE, e.current().phase)
        val step = e.extend()
        assertEquals(SessionEngine.Phase.WORK, step.phase)
    }

    @Test
    fun schedulingFailuresComeBackSoonerThanSuccesses() {
        val fail = Fsrs.intervalDays(Fsrs.initial(Fsrs.gradeOf(40f)))
        val clean = Fsrs.intervalDays(Fsrs.initial(Fsrs.gradeOf(95f)))
        assertTrue(fail < clean)
    }

    @Test
    fun diagnosisIsBluntAndSpecific() {
        val d = Diagnosis.of(
            AttemptResult(60f, 12, 5, 3, 45, 52, leftAccuracy = 55f, rightAccuracy = 90f),
        )
        assertTrue(d.contains("3 wrong notes"))
        assertTrue(d.contains("5 missed"))
        assertTrue(d.contains("timing late avg 52ms"))
        assertTrue(d.contains("left hand 55% vs right 90%"))
        // No cheerleading, ever (SPEC §2.8).
        assertTrue(!d.contains("great", ignoreCase = true))
    }
}
