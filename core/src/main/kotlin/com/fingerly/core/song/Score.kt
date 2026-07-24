package com.fingerly.core.song

/** One playable note of a parsed piece. Times are in seconds from piece start. */
class ChartNote(
    val midiNote: Int,
    val startSeconds: Double,
    var durationSeconds: Double, // var: tie merging extends it during parse
    /** [HAND_RIGHT] (staff 1) or [HAND_LEFT] (staff 2+). */
    val hand: Int,
    val measure: Int,
) {
    companion object {
        const val HAND_RIGHT = 0
        const val HAND_LEFT = 1
    }
}

/** A parsed piece: notes sorted by start time, then pitch. */
class Score(
    val title: String,
    val notes: List<ChartNote>,
    val tempoBpm: Double,
    val beatsPerBar: Int,
    val totalSeconds: Double,
)
