package com.fingerly.core.notation

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score

/**
 * The cold-read corpus (SPEC §4a-F item F3).
 *
 * The terminal item is "an unseen 4-bar excerpt in the C3–G4 span, notation
 * only, no scaffold, played hands-together at a stated slow tempo". That
 * demands a bank of excerpts and a record of which have been consumed, because
 * *unseen* has to be enforceable or the measurement is worthless.
 *
 * Excerpts are written in a one-token-per-beat spelling rather than MusicXML:
 * the vocabulary is deliberately tiny (quarters, halves, wholes, rests, no
 * accidentals) and 20 hand-written MusicXML files would be 2,000 lines of noise
 * around 200 notes of actual content. Hand assignment is explicit here rather
 * than inferred from pitch — inferring it is precisely the mistake that got the
 * fingering atoms deleted.
 *
 * Density is graded on purpose. If the probe floors at zero for a month the
 * falsification check cannot distinguish "the atoms do not transfer" from "the
 * instrument was too hard to register anything", and a null result that cannot
 * be interpreted is worse than no result.
 */
object ExcerptBank {

    /** Below this the two parts are not separately observable — MIDI has no hands. */
    const val MIN_HAND_SEPARATION_SEMITONES = 14

    /** Lowest and highest pitch any excerpt may use. */
    const val SPAN_LOW = 43 // G2 — the left hand of the bundled Ode to Joy
    const val SPAN_HIGH = 67 // G4

    /**
     * Four landmark notes in four bars, nothing else. This tier exists so the
     * instrument has a FLOOR a week-one beginner can actually reach: an excerpt
     * of fifteen stepwise notes is not a hard test for someone who cannot find
     * middle C, it is an unanswerable one, and an unanswerable test produces a
     * flat zero for weeks that says nothing about whether anything is working.
     */
    const val TIER_ANCHORS = 0
    const val TIER_STEPWISE = 1
    const val TIER_SKIPS = 2
    const val TIER_HANDS_SUSTAINED = 3
    const val TIER_HANDS_MOVING = 4

    class Note(
        val midi: Int,
        val startBeat: Double,
        val durationBeats: Double,
        val hand: Int,
    )

    class Excerpt(
        val id: String,
        val tier: Int,
        val notes: List<Note>,
        val bars: Int = 4,
        val beatsPerBar: Int = 4,
    ) {
        val totalBeats: Double get() = (bars * beatsPerBar).toDouble()

        val handsTogether: Boolean get() = tier >= TIER_HANDS_SUSTAINED

        /**
         * Onsets where both hands sound at once AND are far enough apart to be
         * separately observable. Only these can evidence hands-together
         * playing; anything closer could have been played with one hand.
         */
        fun scorableHandsTogetherOnsets(): Int {
            val rights = notes.filter { it.hand == ChartNote.HAND_RIGHT }
            val lefts = notes.filter { it.hand == ChartNote.HAND_LEFT }
            var n = 0
            for (r in rights) {
                // A left-hand note counts if it is SOUNDING at the right hand's
                // onset, not merely if it starts at the same instant — a held
                // bass note under a melody is the normal texture.
                val sounding = lefts.any {
                    r.startBeat >= it.startBeat - 1e-9 &&
                        r.startBeat < it.startBeat + it.durationBeats - 1e-9
                }
                if (!sounding) continue
                val nearest = lefts.filter {
                    r.startBeat >= it.startBeat - 1e-9 &&
                        r.startBeat < it.startBeat + it.durationBeats - 1e-9
                }.maxOf { it.midi }
                if (r.midi - nearest >= MIN_HAND_SEPARATION_SEMITONES) n++
            }
            return n
        }

        /** The excerpt as a playable [Score] — same type the song path consumes. */
        fun toScore(tempoBpm: Double = COLD_READ_TEMPO_BPM): Score {
            val secPerBeat = 60.0 / tempoBpm
            val chart = notes
                .sortedWith(compareBy({ it.startBeat }, { it.midi }))
                .map {
                    ChartNote(
                        midiNote = it.midi,
                        startSeconds = it.startBeat * secPerBeat,
                        durationSeconds = it.durationBeats * secPerBeat,
                        hand = it.hand,
                        measure = (it.startBeat / beatsPerBar).toInt() + 1,
                    )
                }
            return Score(id, chart, tempoBpm, beatsPerBar, totalBeats * secPerBeat)
        }
    }

    /**
     * The stated slow tempo of the cold read. Slow enough that reading, not
     * dexterity, is the bottleneck — the read is the thing being measured.
     */
    const val COLD_READ_TEMPO_BPM = 50.0

    /** Milliseconds per beat at the cold-read tempo. */
    const val COLD_READ_BEAT_MS = 60_000.0 / COLD_READ_TEMPO_BPM

    /**
     * The pitch-attribution window for a cold read, deliberately WIDE.
     *
     * Conditions 1 and 3 of the definition of done are separate — "≥90% correct
     * pitches" and "played in time" — and the default ±150ms window collapses
     * them into one. At this tempo a beat is 1200ms, so a beginner who reads
     * every pitch correctly but arrives half a second late would score zero
     * pitch accuracy AND take an extra-note penalty for each one: the
     * instrument would report "cannot read music" about someone who just read
     * it slowly.
     *
     * So pitch is attributed generously and timing is reported separately as
     * error magnitude. The judge attributes a press to the CLOSEST pending
     * match, so a wide window does not smear notes onto their neighbours.
     */
    const val COLD_READ_HIT_WINDOW_MS = 900L

    /** Must exceed the window, or a note is retired while still reachable. */
    const val COLD_READ_MISS_AFTER_MS = 1500L

    /** One full bar of count-in before the first note. */
    const val COLD_READ_LEAD_IN_MS = 4L * COLD_READ_BEAT_MS.toLong()

    /**
     * One token per beat. A pitch token starts a note; `-` extends the previous
     * note by one beat; `.` is a beat of silence; `|` is a barline and is only
     * checked, never sounded.
     */
    fun parseLine(spec: String, hand: Int): List<Note> {
        val out = ArrayList<Note>()
        var beat = 0.0
        for (token in spec.trim().split(Regex("\\s+"))) {
            when (token) {
                "|", "" -> continue
                "-" -> {
                    require(out.isNotEmpty()) { "'-' with nothing to extend in: $spec" }
                    val last = out.removeAt(out.size - 1)
                    out.add(Note(last.midi, last.startBeat, last.durationBeats + 1.0, hand))
                    beat += 1.0
                }
                "." -> beat += 1.0
                else -> {
                    out.add(Note(midiOf(token), beat, 1.0, hand))
                    beat += 1.0
                }
            }
        }
        return out
    }

    /** Scientific pitch notation → MIDI. Naturals only; the module has no accidentals. */
    fun midiOf(name: String): Int {
        val letter = name[0].uppercaseChar()
        val octave = name.substring(1).toInt()
        val semis = when (letter) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5
            'G' -> 7; 'A' -> 9; 'B' -> 11
            else -> throw IllegalArgumentException("not a natural pitch: $name")
        }
        return (octave + 1) * 12 + semis
    }

    private fun excerpt(id: String, tier: Int, right: String? = null, left: String? = null): Excerpt {
        val notes = ArrayList<Note>()
        if (right != null) notes.addAll(parseLine(right, ChartNote.HAND_RIGHT))
        if (left != null) notes.addAll(parseLine(left, ChartNote.HAND_LEFT))
        return Excerpt(id, tier, notes)
    }

    /**
     * Twenty excerpts, five per tier. Every one is four bars of 4/4 inside
     * G2–G4, using only quarters, halves and wholes.
     */
    val all: List<Excerpt> = listOf(
        // --- Tier 0: landmarks only, one note per bar -------------------------
        // Answerable by someone who knows exactly one thing: where middle C is.
        excerpt("a0-middle-c", TIER_ANCHORS,
            right = "C4 - - - | C4 - - - | C4 - - - | C4 - - -"),
        excerpt("a0-c-and-g", TIER_ANCHORS,
            right = "C4 - - - | G4 - - - | C4 - - - | G4 - - -"),
        excerpt("a0-c-g-halves", TIER_ANCHORS,
            right = "C4 - G4 - | C4 - G4 - | G4 - C4 - | C4 - - -"),
        excerpt("a0-bass-f", TIER_ANCHORS,
            left = "F3 - - - | F3 - - - | F3 - - - | F3 - - -"),
        excerpt("a0-both-anchors", TIER_ANCHORS,
            right = "C4 - - - | . . . . | G4 - - - | . . . .",
            left = ". . . . | F3 - - - | . . . . | F3 - - -"),

        // --- Tier 1: right hand, stepwise, five-finger position ---------------
        excerpt("t0-scale-up", TIER_STEPWISE,
            "C4 D4 E4 F4 | G4 - - - | F4 E4 D4 C4 | C4 - - -"),
        excerpt("t0-ode", TIER_STEPWISE,
            "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4 - D4 -"),
        excerpt("t0-turn", TIER_STEPWISE,
            "C4 D4 E4 D4 | C4 - - - | D4 E4 F4 E4 | D4 - - -"),
        excerpt("t0-descend", TIER_STEPWISE,
            "G4 F4 E4 D4 | C4 - - - | D4 E4 F4 G4 | G4 - - -"),
        excerpt("t0-rest", TIER_STEPWISE,
            "C4 D4 E4 . | F4 G4 F4 . | E4 D4 C4 . | C4 - - -"),

        // --- Tier 1: right hand, skips and a wider reach ----------------------
        excerpt("t1-triad", TIER_SKIPS,
            "C4 E4 G4 E4 | C4 - - - | D4 F4 G4 F4 | D4 - - -"),
        excerpt("t1-leap", TIER_SKIPS,
            "C4 G4 E4 C4 | D4 - - - | E4 G4 F4 D4 | C4 - - -"),
        excerpt("t1-lowreach", TIER_SKIPS,
            "E4 C4 D4 B3 | C4 - - - | E4 D4 C4 B3 | C4 - - -"),
        excerpt("t1-arch", TIER_SKIPS,
            "C4 E4 G4 - | F4 D4 C4 - | E4 G4 F4 D4 | C4 - - -"),
        excerpt("t1-wide", TIER_SKIPS,
            "A3 C4 E4 G4 | E4 - C4 - | G4 E4 C4 A3 | C4 - - -"),

        // --- Tier 2: hands together, left hand sustained ----------------------
        excerpt("t2-tonic", TIER_HANDS_SUSTAINED,
            right = "E4 F4 G4 - | F4 E4 D4 - | E4 D4 C4 - | D4 - - -",
            left = "C3 - - - | G2 - - - | C3 - - - | G2 - - -"),
        excerpt("t2-alternating", TIER_HANDS_SUSTAINED,
            right = "G4 F4 E4 D4 | E4 - - - | F4 E4 D4 E4 | D4 - - -",
            left = "C3 - - - | G2 - - - | G2 - - - | C3 - - -"),
        excerpt("t2-held", TIER_HANDS_SUSTAINED,
            right = "D4 E4 F4 G4 | F4 - E4 - | D4 E4 F4 E4 | D4 - - -",
            left = "G2 - - - | G2 - - - | C3 - - - | C3 - - -"),
        excerpt("t2-sparse", TIER_HANDS_SUSTAINED,
            right = "E4 - D4 - | F4 - E4 - | G4 - F4 - | E4 - - -",
            left = "C3 - - - | C3 - - - | G2 - - - | C3 - - -"),
        excerpt("t2-climb", TIER_HANDS_SUSTAINED,
            right = "D4 E4 F4 G4 | G4 F4 E4 D4 | E4 F4 G4 F4 | E4 - - -",
            left = "G2 - - - | C3 - - - | G2 - - - | C3 - - -"),

        // --- Tier 3: hands together, left hand moving -------------------------
        excerpt("t3-walk", TIER_HANDS_MOVING,
            right = "G4 F4 E4 D4 | E4 - - - | F4 E4 D4 E4 | D4 - - -",
            left = "C3 - G2 - | C3 - - - | G2 - C3 - | G2 - - -"),
        excerpt("t3-answer", TIER_HANDS_MOVING,
            right = "E4 F4 G4 - | E4 D4 E4 - | F4 G4 F4 E4 | D4 - - -",
            left = "C3 - G2 - | G2 - C3 - | C3 - G2 - | C3 - - -"),
        excerpt("t3-pulse", TIER_HANDS_MOVING,
            right = "F4 E4 D4 E4 | F4 G4 F4 E4 | D4 E4 F4 E4 | D4 - - -",
            left = "C3 C3 G2 G2 | C3 C3 G2 G2 | C3 - G2 - | C3 - - -"),
        excerpt("t3-contrary", TIER_HANDS_MOVING,
            right = "D4 E4 F4 G4 | F4 E4 D4 - | E4 F4 G4 F4 | E4 - - -",
            left = "C3 - B2 - | A2 - G2 - | A2 - B2 - | C3 - - -"),
        excerpt("t3-close", TIER_HANDS_MOVING,
            right = "G4 E4 F4 D4 | E4 - D4 - | F4 D4 E4 F4 | E4 - - -",
            left = "C3 - G2 - | C3 - G2 - | G2 - C3 - | C3 - - -"),
    )

    private val byId = all.associateBy { it.id }

    /**
     * The next unseen excerpt, easiest tier first. Returns null only when the
     * whole bank has been consumed — the caller must surface that rather than
     * silently re-serving, because a second sighting is no longer a cold read.
     */
    fun nextUnseen(consumed: Set<String>): Excerpt? =
        all.filter { it.id !in consumed }.minByOrNull { it.tier }

    fun byId(id: String): Excerpt? = byId[id]

    /** Serialised consumed-set. Kept as ids so reordering the bank is harmless. */
    fun encodeConsumed(consumed: Set<String>): String = consumed.sorted().joinToString(",")

    fun decodeConsumed(s: String?): Set<String> =
        s?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
}
