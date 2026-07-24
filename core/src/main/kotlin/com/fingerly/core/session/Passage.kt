package com.fingerly.core.session

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score

/** Hand filter for practice: [ChartNote.HAND_RIGHT], [ChartNote.HAND_LEFT] or [HAND_BOTH]. */
const val HAND_BOTH = 2

/**
 * A micro-passage: a 2–8 bar slice of a song (SPEC §3/§4). Note times are
 * absolute piece time; slicing/rebasing happens in [PracticeScoreFactory].
 */
class Passage(
    val id: Int, // stable index within the song's decomposition
    val startMeasure: Int,
    val endMeasure: Int, // inclusive
    val notes: List<ChartNote>,
    /** Position in the difficulty ordering, 0 = easiest (SPEC §4). */
    val difficultyRank: Int,
) {
    val barCount: Int get() = endMeasure - startMeasure + 1
}

/** One rung of the auto-difficulty ladder (SPEC §3). */
data class PracticeSetting(
    val tempoMultiplier: Double, // 1.0 = full tempo
    val hand: Int, // HAND_BOTH or a single hand
    /** Bars from the passage start to practice (shrunk when struggling). */
    val bars: Int,
    /**
     * Wait mode: the song pauses at each note until the right key is pressed —
     * key-finding is learned before timing pressure exists. Only correctness
     * is graded.
     */
    val wait: Boolean = false,
)

/** Builds the playable score for one passage at one difficulty setting. */
object PracticeScoreFactory {

    fun build(source: Score, passage: Passage, setting: PracticeSetting): Score {
        val lastMeasure = passage.startMeasure + setting.bars - 1
        val slice = passage.notes.filter { n ->
            n.measure <= lastMeasure &&
                (setting.hand == HAND_BOTH || n.hand == setting.hand)
        }
        val t0 = slice.minOfOrNull { it.startSeconds } ?: 0.0
        val stretch = 1.0 / setting.tempoMultiplier // slower = longer
        val notes = slice.map { n ->
            ChartNote(
                midiNote = n.midiNote,
                startSeconds = (n.startSeconds - t0) * stretch,
                durationSeconds = n.durationSeconds * stretch,
                hand = n.hand,
                measure = n.measure,
            )
        }
        return Score(
            title = source.title,
            notes = notes.sortedWith(compareBy({ it.startSeconds }, { it.midiNote })),
            tempoBpm = source.tempoBpm * setting.tempoMultiplier,
            beatsPerBar = source.beatsPerBar,
            totalSeconds = notes.maxOfOrNull { it.startSeconds + it.durationSeconds } ?: 0.0,
        )
    }
}
