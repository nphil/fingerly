package com.fingerly.core.session

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score

/**
 * Foundations course (SPEC §2.5/§4): the keyboard mental map, note names,
 * octaves, finger numbers and hand position — taught through the hands via
 * wait-mode key-finding runs, never through study. Intros obey the 3-sentence
 * cap (SPEC §2.4). Gate: runs before song sessions until completed once.
 */
object Foundations {

    class Lesson(
        val id: String,
        val title: String,
        /** Max 3 sentences (SPEC §2.4). Exact and verifiable (SPEC §2.5). */
        val intro: String,
        val score: Score,
    )

    const val PASS_ACCURACY = 90f

    fun lessons(): List<Lesson> = listOf(
        Lesson(
            id = "find-c",
            title = "Finding C",
            intro = "Black keys come in groups of 2 and 3. " +
                "Every C is the white key just LEFT of a group of 2. " +
                "Find each C as it lights up — take your time, nothing is timed.",
            score = drill("Finding C", listOf(60, 48, 72, 36, 84, 60)),
        ),
        Lesson(
            id = "white-keys",
            title = "The seven letters",
            intro = "White keys repeat seven letters: C D E F G A B, then C again. " +
                "Walk up from middle C one key at a time, then back down.",
            score = drill(
                "The seven letters",
                listOf(60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60),
            ),
        ),
        Lesson(
            id = "octaves",
            title = "Octaves",
            intro = "The same letter repeats every 12 keys — that distance is an octave. " +
                "Play the same letter in different places and feel the pattern repeat.",
            score = drill("Octaves", listOf(60, 72, 48, 67, 79, 55, 64, 76, 52)),
        ),
        Lesson(
            id = "right-hand-position",
            title = "Right hand: C position",
            intro = "Fingers are numbered thumb=1 to little finger=5. " +
                "Rest your right hand with thumb (1) on middle C and one finger per key up to G. " +
                "Press each lit key with the finger already sitting on it — don't move the hand.",
            score = drill(
                "Right hand: C position",
                listOf(60, 62, 64, 65, 67, 64, 60, 65, 62, 67, 60),
            ),
        ),
        Lesson(
            id = "left-hand-position",
            title = "Left hand: C position",
            intro = "Left hand mirrors: little finger (5) on the C below middle C, thumb (1) on G. " +
                "Same rule — press with the finger sitting on the key. " +
                "Curve your fingers like holding a ball; keep wrists level and shoulders loose.",
            score = drill(
                "Left hand: C position",
                listOf(48, 50, 52, 53, 55, 52, 48, 53, 50, 55, 48),
                hand = ChartNote.HAND_LEFT,
            ),
        ),
    )

    /** One note every [SPACING_SEC]s; wait mode pauses at each anyway. */
    private fun drill(
        title: String,
        midiNotes: List<Int>,
        hand: Int = ChartNote.HAND_RIGHT,
    ): Score {
        val notes = midiNotes.mapIndexed { i, midi ->
            ChartNote(
                midiNote = midi,
                startSeconds = i * SPACING_SEC,
                durationSeconds = 1.0,
                hand = if (midi < 60 && hand == ChartNote.HAND_RIGHT) ChartNote.HAND_LEFT else hand,
                measure = i + 1,
            )
        }
        return Score(
            title = title,
            notes = notes,
            tempoBpm = 60.0,
            beatsPerBar = 4,
            totalSeconds = midiNotes.size * SPACING_SEC,
        )
    }

    private const val SPACING_SEC = 2.0
}
