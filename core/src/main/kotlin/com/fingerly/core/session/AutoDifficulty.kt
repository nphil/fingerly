package com.fingerly.core.session

/**
 * The auto-difficulty ladder (SPEC §3): holds the learner near an 85% success
 * rate by stepping along one axis at a time — slower tempo → split hands →
 * fewer bars — and climbing back up on success. The user never hits a wall and
 * never chooses a difficulty (SPEC §2 anti-patterns: no decision menus).
 */
object AutoDifficulty {

    /** Below this accuracy the setting steps easier. */
    const val STEP_DOWN_BELOW = 70f

    /** At or above this accuracy the setting steps harder. */
    const val STEP_UP_AT = 92f

    /** Accuracy that counts a rung as "clean" (finish states, mastery). */
    const val CLEAN_AT = 85f

    /**
     * Ladder for a passage, hardest (index 0 = performance target) to easiest.
     * Single-hand rungs and shrunk-bar rungs use the learner's mistake axis:
     * whichever hand measured worse is practiced first.
     */
    fun ladder(passage: Passage, weakerHand: Int): List<PracticeSetting> {
        val all = passage.barCount
        val half = (all / 2).coerceAtLeast(2).coerceAtMost(all)
        val strongerHand = if (weakerHand == com.fingerly.core.song.ChartNote.HAND_LEFT) {
            com.fingerly.core.song.ChartNote.HAND_RIGHT
        } else {
            com.fingerly.core.song.ChartNote.HAND_LEFT
        }
        return listOf(
            PracticeSetting(1.0, HAND_BOTH, all),
            PracticeSetting(0.85, HAND_BOTH, all),
            PracticeSetting(0.7, HAND_BOTH, all),
            PracticeSetting(0.55, HAND_BOTH, all),
            PracticeSetting(0.7, strongerHand, all),
            PracticeSetting(0.7, weakerHand, all),
            PracticeSetting(0.55, strongerHand, all),
            PracticeSetting(0.55, weakerHand, all),
            PracticeSetting(0.55, strongerHand, half),
            PracticeSetting(0.55, weakerHand, half),
        )
    }

    /** Rung a passage with no history starts on: one hand, slow, all bars. */
    const val FRESH_START_INDEX = 6

    /**
     * Next ladder index after an attempt. [index] 0 is hardest; larger = easier.
     */
    fun adjust(index: Int, lastIndex: Int, accuracyPercent: Float): Int = when {
        accuracyPercent < STEP_DOWN_BELOW -> (index + 1).coerceAtMost(lastIndex)
        accuracyPercent >= STEP_UP_AT -> (index - 1).coerceAtLeast(0)
        else -> index
    }
}
