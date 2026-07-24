package com.fingerly.core.session

import com.fingerly.core.song.BundledSongs
import com.fingerly.core.song.ChartNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerProfileTest {

    private fun record(
        skills: Set<String>,
        acc: Float,
        signedErr: Long = 0,
        left: Float = -1f,
        right: Float = -1f,
        t: Long = 0,
    ) = LearnerProfile.AttemptRecord(skills, acc, signedErr, left, right, t)

    @Test
    fun weakSkillsSurfaceOnlyWithEnoughData() {
        val records = listOf(
            record(setOf("leaps"), 50f, t = 1),
            record(setOf("leaps"), 55f, t = 2),
            record(setOf("leaps"), 60f, t = 3),
            record(setOf("chords"), 40f, t = 4), // only one attempt: not trusted yet
            record(setOf("hands-together"), 95f, t = 5),
            record(setOf("hands-together"), 96f, t = 6),
            record(setOf("hands-together"), 97f, t = 7),
        )
        val report = LearnerProfile.analyze(records)
        assertEquals(listOf("leaps"), report.weakestSkills(minAttempts = 3))
    }

    @Test
    fun timingBiasAndHandsTracked() {
        val records = listOf(
            record(setOf("leaps"), 80f, signedErr = 60, left = 50f, right = 90f, t = 1),
            record(setOf("leaps"), 82f, signedErr = 50, left = 55f, right = 92f, t = 2),
        )
        val report = LearnerProfile.analyze(records)
        assertTrue(report.timingBiasMs > 30) // consistently late
        assertEquals(ChartNote.HAND_LEFT, report.weakerHand())
    }

    @Test
    fun bundledSongsGetSensibleSkillTags() {
        val ode = Decomposer.decompose(BundledSongs.odeToJoyBeginner())
        // Beginner arrangement: hands together, but no chords/leaps/black keys
        // in the melody+roots texture... roots do leap (C3->G2 is a 5th+).
        ode.forEach { p ->
            assertTrue(p.skills.contains(Decomposer.SKILL_HANDS_TOGETHER))
            assertTrue(!p.skills.contains(Decomposer.SKILL_BLACK_KEYS))
        }

        val gym = Decomposer.decompose(BundledSongs.gymnopedie1Excerpt())
        // Gymnopédie: chords, black keys, and left-hand jumps everywhere.
        assertTrue(gym.any { it.skills.contains(Decomposer.SKILL_CHORDS) })
        assertTrue(gym.any { it.skills.contains(Decomposer.SKILL_BLACK_KEYS) })
        assertTrue(gym.any { it.skills.contains(Decomposer.SKILL_LEAPS) })
    }

    @Test
    fun fsrsWiredIntoSessionEngine() {
        val passages = Decomposer.decompose(BundledSongs.odeToJoyBeginner())
        val progress = HashMap<Int, PassageProgress>()
        var clock = 0L
        val e = SessionEngine(passages, progress, nowMs = { clock })
        e.begin()
        e.onAttempt(
            AttemptResult(90f, 10, 1, 0, 20, 5, 90f, 90f),
        )
        val pr = progress.values.first()
        assertTrue(pr.stability > 0)
        assertTrue(pr.dueAtMs > 0)
        val firstDue = pr.dueAtMs

        // A later clean review pushes the due date further out.
        clock += 12 * 60 * 60 * 1000L
        e.onAttempt(AttemptResult(96f, 10, 0, 0, 15, 3, 96f, 96f))
        assertTrue(progress.values.first().dueAtMs > firstDue)
    }
}
