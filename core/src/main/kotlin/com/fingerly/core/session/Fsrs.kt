package com.fingerly.core.session

import kotlin.math.exp
import kotlin.math.pow

/**
 * FSRS-4.5 scheduler (SPEC §3: FSRS preferred), adapted for motor skills: the
 * grade is derived from measured accuracy — never self-report (SPEC §2.8).
 * Weights start at the published FSRS-4.5 defaults; §8a personalization can
 * refit them from this learner's review history later.
 */
object Fsrs {

    const val GRADE_AGAIN = 1
    const val GRADE_HARD = 2
    const val GRADE_GOOD = 3
    const val GRADE_EASY = 4

    /** Desired recall probability at review time. */
    private const val TARGET_RETENTION = 0.9

    private val W = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031,
        1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
    )

    /** Card state; stability in days, difficulty 1..10. */
    data class Card(val stability: Double, val difficulty: Double)

    /** Measured accuracy → FSRS grade (SPEC §3: grade = measurement). */
    fun gradeOf(accuracyPercent: Float): Int = when {
        accuracyPercent < 70f -> GRADE_AGAIN
        accuracyPercent < 85f -> GRADE_HARD
        accuracyPercent < 95f -> GRADE_GOOD
        else -> GRADE_EASY
    }

    /** First review of a passage. */
    fun initial(grade: Int): Card = Card(
        stability = W[grade - 1].coerceAtLeast(0.1),
        difficulty = initialDifficulty(grade),
    )

    /** Subsequent review after [elapsedDays]. */
    fun review(card: Card, grade: Int, elapsedDays: Double): Card {
        val r = retrievability(card.stability, elapsedDays)
        val d = nextDifficulty(card.difficulty, grade)
        val s = if (grade == GRADE_AGAIN) {
            (W[11] * card.difficulty.pow(-W[12]) *
                ((card.stability + 1).pow(W[13]) - 1) * exp(W[14] * (1 - r)))
                .coerceIn(0.1, card.stability)
        } else {
            val hardPenalty = if (grade == GRADE_HARD) W[15] else 1.0
            val easyBonus = if (grade == GRADE_EASY) W[16] else 1.0
            card.stability * (
                1 + exp(W[8]) * (11 - d) * card.stability.pow(-W[9]) *
                    (exp(W[10] * (1 - r)) - 1) * hardPenalty * easyBonus
                )
        }
        return Card(stability = s.coerceIn(0.1, 36500.0), difficulty = d)
    }

    /** Days until recall probability decays to [TARGET_RETENTION]. */
    fun intervalDays(card: Card): Double =
        (9.0 * card.stability * (1.0 / TARGET_RETENTION - 1.0)).coerceIn(0.25, 365.0)

    fun retrievability(stability: Double, elapsedDays: Double): Double =
        (1.0 + elapsedDays / (9.0 * stability)).pow(-1.0)

    private fun initialDifficulty(grade: Int): Double =
        (W[4] - (grade - 3) * W[5]).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, grade: Int): Double {
        val updated = d - W[6] * (grade - 3)
        // Mean reversion toward the default-difficulty anchor.
        return (W[7] * initialDifficulty(GRADE_GOOD) + (1 - W[7]) * updated)
            .coerceIn(1.0, 10.0)
    }

}
