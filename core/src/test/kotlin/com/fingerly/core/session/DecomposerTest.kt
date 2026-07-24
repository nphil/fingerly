package com.fingerly.core.session

import com.fingerly.core.song.BundledSongs
import com.fingerly.core.song.ChartNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecomposerTest {

    private val score = BundledSongs.gymnopedie1Excerpt() // 14 bars

    @Test
    fun chunksAreWithinTwoToEightBars() {
        val passages = Decomposer.decompose(score, targetBars = 4)
        assertTrue(passages.isNotEmpty())
        passages.forEach { assertTrue("bars=${it.barCount}", it.barCount in 2..8) }
        // 14 bars at target 4 → 4+4+4+2 or 4+4+6: full coverage either way.
        assertEquals(1, passages.first().startMeasure)
        assertEquals(14, passages.last().endMeasure)
        // Contiguous, non-overlapping.
        passages.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endMeasure + 1, b.startMeasure)
        }
    }

    @Test
    fun everyNoteLandsInExactlyOnePassage() {
        val passages = Decomposer.decompose(score)
        assertEquals(score.notes.size, passages.sumOf { it.notes.size })
    }

    @Test
    fun introRanksEasierThanMelody() {
        val passages = Decomposer.decompose(score, targetBars = 4)
        val intro = passages.first { it.startMeasure == 1 } // LH only
        val melody = passages.first { it.startMeasure == 5 } // both hands
        assertTrue(intro.difficultyRank < melody.difficultyRank)
    }

    @Test
    fun practiceScoreFactoryFiltersAndStretches() {
        val passages = Decomposer.decompose(score, targetBars = 4)
        val melody = passages.first { it.startMeasure == 5 }

        val full = PracticeScoreFactory.build(
            score, melody, PracticeSetting(1.0, HAND_BOTH, melody.barCount),
        )
        assertEquals(melody.notes.size, full.notes.size)
        assertEquals(0.0, full.notes.minOf { it.startSeconds }, 1e-9) // rebased

        val rightSlow = PracticeScoreFactory.build(
            score, melody, PracticeSetting(0.5, ChartNote.HAND_RIGHT, melody.barCount),
        )
        assertTrue(rightSlow.notes.all { it.hand == ChartNote.HAND_RIGHT })
        // Half tempo = twice the wall-clock length.
        val fullRight = full.notes.filter { it.hand == ChartNote.HAND_RIGHT }
        val fullSpan = fullRight.maxOf { it.startSeconds + it.durationSeconds } -
            fullRight.minOf { it.startSeconds }
        val slowSpan = rightSlow.totalSeconds
        assertEquals(fullSpan * 2, slowSpan, 1e-6)

        val twoBars = PracticeScoreFactory.build(
            score, melody, PracticeSetting(1.0, HAND_BOTH, 2),
        )
        assertTrue(twoBars.notes.all { it.measure <= melody.startMeasure + 1 })
    }
}
