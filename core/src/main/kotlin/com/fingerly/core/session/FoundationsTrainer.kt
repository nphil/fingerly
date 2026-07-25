package com.fingerly.core.session

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import kotlin.random.Random

/**
 * Adaptive foundations trainer (SPEC §8a applied to raw basics), designed for
 * an ADHD learner per the literature:
 *
 *  - drills are SHORT (≤ [DRILL_LENGTH] prompts ≈ 45s) and structurally
 *    identical every time (predictability);
 *  - the atom map is always visible first (big picture, externalized progress);
 *  - each drill mixes ~1/3 already-strong material into the weak focus
 *    (interleaving + high success rate, protects engagement);
 *  - in-the-moment feedback stays gentle; teaching happens in blunt
 *    between-drill summaries (feedback-timing findings in adult ADHD);
 *  - mastery = fast AND accurate (response latency counts, not just hits);
 *  - ends with a mixed checkpoint test and a strengths/weaknesses report,
 *    and drills continue until the data shows true mastery.
 *
 * Error taxonomy per wrong press: octave error (right letter, wrong octave),
 * neighbor error (adjacent key — discrimination), other. The dominant recent
 * error type selects the micro-tip (≤3 sentences, SPEC §2.4).
 */
class FoundationsTrainer(serialized: String? = null) {

    class AtomStats {
        var emaAcc = -1f // first-try-correct rate
        var emaLatMs = -1f // time from prompt to correct key
        var n = 0
        var octaveErrors = 0
        var neighborErrors = 0
        var otherErrors = 0

        fun addPrompt(correctFirstTry: Boolean, latencyMs: Int) {
            val acc = if (correctFirstTry) 1f else 0f
            emaAcc = if (emaAcc < 0) acc else emaAcc * 0.75f + acc * 0.25f
            val lat = latencyMs.coerceAtMost(15_000).toFloat()
            emaLatMs = if (emaLatMs < 0) lat else emaLatMs * 0.75f + lat * 0.25f
            n++
        }

        fun mastered(): Boolean =
            n >= MIN_PROMPTS && emaAcc >= MASTERY_ACC && emaLatMs in 0f..MASTERY_LAT_MS

        /** 0..100 for the progress meter: accuracy tempered by speed. */
        fun masteryPercent(): Int {
            if (n == 0) return 0
            val accPart = (emaAcc.coerceIn(0f, 1f)) * 70f
            val latPart = if (emaLatMs <= 0) 0f else
                (1f - ((emaLatMs - MASTERY_LAT_MS) / 6000f).coerceIn(0f, 1f)) * 30f
            return (accPart + latPart).toInt().coerceIn(0, 100)
        }

        fun serialize(): String = "$emaAcc:$emaLatMs:$n:$octaveErrors:$neighborErrors:$otherErrors"

        companion object {
            fun deserialize(s: String): AtomStats = AtomStats().apply {
                val p = s.split(':')
                if (p.size == 6) {
                    emaAcc = p[0].toFloatOrNull() ?: -1f
                    emaLatMs = p[1].toFloatOrNull() ?: -1f
                    n = p[2].toIntOrNull() ?: 0
                    octaveErrors = p[3].toIntOrNull() ?: 0
                    neighborErrors = p[4].toIntOrNull() ?: 0
                    otherErrors = p[5].toIntOrNull() ?: 0
                }
            }
        }
    }

    class Prompt(val atomId: String, val midiNote: Int)

    class Drill(
        val focusAtom: String,
        val title: String,
        /** ≤3 sentences (SPEC §2.4); null once the concept has been introduced. */
        val tip: String?,
        val prompts: List<Prompt>,
        val isTest: Boolean,
    )

    class PromptResult(
        val atomId: String,
        val correctFirstTry: Boolean,
        val latencyMs: Int,
        val expectedNote: Int,
        val wrongPresses: List<Int>,
    )

    val atoms = LinkedHashMap<String, AtomStats>()
    private var totalDrills = 0

    init {
        for (id in ATOM_IDS) atoms[id] = AtomStats()
        serialized?.split(';')?.forEach { entry ->
            val i = entry.indexOf('=')
            if (i > 0) {
                val id = entry.substring(0, i)
                if (id == "drills") {
                    totalDrills = entry.substring(i + 1).toIntOrNull() ?: 0
                } else if (id in atoms) {
                    atoms[id] = AtomStats.deserialize(entry.substring(i + 1))
                }
            }
        }
    }

    fun serialize(): String =
        atoms.entries.joinToString(";") { "${it.key}=${it.value.serialize()}" } +
            ";drills=$totalDrills"

    fun allMastered(): Boolean = atoms.values.all { it.mastered() }

    /** (id, label, percent, mastered) rows for the always-visible map. */
    fun masteryRows(): List<MasteryRow> =
        atoms.map { (id, st) -> MasteryRow(id, LABELS.getValue(id), st.masteryPercent(), st.mastered()) }

    class MasteryRow(val id: String, val label: String, val percent: Int, val mastered: Boolean)

    fun nextDrill(): Drill {
        if (allMastered()) return buildTest()
        val focus = atoms.entries
            .filter { !it.value.mastered() }
            .minByOrNull { it.value.masteryPercent() }!!.key
        val rng = Random(totalDrills * 7919 + 13)
        val prompts = ArrayList<Prompt>(DRILL_LENGTH)
        val focusCount = DRILL_LENGTH - 3
        repeat(focusCount) { prompts.add(promptFor(focus, rng)) }
        // Pad with strongest material: success moments protect engagement.
        val strong = atoms.entries.sortedByDescending { it.value.masteryPercent() }
            .take(3).map { it.key }
        repeat(3) { i -> prompts.add(promptFor(strong[i % strong.size], rng)) }
        prompts.shuffle(rng)
        // Never start a drill with an unseen hard prompt: lead with focus after intro.
        totalDrills++
        return Drill(
            focusAtom = focus,
            title = LABELS.getValue(focus),
            tip = tipFor(focus),
            prompts = prompts,
            isTest = false,
        )
    }

    private fun buildTest(): Drill {
        val rng = Random(totalDrills * 104729 + 7)
        val prompts = ArrayList<Prompt>()
        for (id in ATOM_IDS) {
            prompts.add(promptFor(id, rng))
            prompts.add(promptFor(id, rng))
        }
        prompts.shuffle(rng)
        totalDrills++
        return Drill(
            focusAtom = "test",
            title = "Checkpoint",
            tip = null,
            prompts = prompts.take(14),
            isTest = true,
        )
    }

    fun recordResults(results: List<PromptResult>) {
        for (r in results) {
            val st = atoms[r.atomId] ?: continue
            st.addPrompt(r.correctFirstTry, r.latencyMs)
            for (wrong in r.wrongPresses) {
                val delta = wrong - r.expectedNote
                when {
                    delta != 0 && delta % 12 == 0 -> st.octaveErrors++
                    kotlin.math.abs(delta) <= 2 -> st.neighborErrors++
                    else -> st.otherErrors++
                }
            }
        }
    }

    /** Did a checkpoint pass? ≥93% first-try over the test prompts. */
    fun testPassed(results: List<PromptResult>): Boolean {
        if (results.isEmpty()) return false
        val firstTry = results.count { it.correctFirstTry }
        return firstTry * 100 / results.size >= 93
    }

    /** Blunt strengths/weaknesses report (SPEC §2.8). */
    fun report(): String {
        val mastered = atoms.filter { it.value.mastered() }.keys.map { LABELS.getValue(it) }
        val weak = atoms.filter { !it.value.mastered() }
            .entries.sortedBy { it.value.masteryPercent() }
        val sb = StringBuilder()
        if (mastered.isNotEmpty()) sb.append("Solid: ${mastered.joinToString(", ")}. ")
        if (weak.isEmpty()) {
            sb.append("All basics measured at mastery — fast and accurate.")
        } else {
            sb.append("Still weak: ")
            sb.append(
                weak.joinToString(", ") {
                    "${LABELS.getValue(it.key)} ${it.value.masteryPercent()}%"
                },
            )
            sb.append('.')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ prompts

    private fun promptFor(atomId: String, rng: Random): Prompt = when (atomId) {
        "octaves" -> {
            val letter = WHITE_SEMITONES[rng.nextInt(WHITE_SEMITONES.size)]
            Prompt(atomId, 12 * (rng.nextInt(4) + 2) + 12 + letter) // octaves 2..5
        }

        "rh-position" -> Prompt(atomId, RH_POSITION[rng.nextInt(RH_POSITION.size)])
        "lh-position" -> Prompt(atomId, LH_POSITION[rng.nextInt(LH_POSITION.size)])
        else -> { // find-<letter>
            val semitone = LETTER_SEMITONE.getValue(atomId.removePrefix("find-"))
            Prompt(atomId, 12 * (rng.nextInt(4) + 2) + 12 + semitone) // octaves 2..5
        }
    }

    /** Micro-tip chosen by the atom's dominant error type; intro tip when new. */
    private fun tipFor(atomId: String): String? {
        val st = atoms.getValue(atomId)
        if (st.n == 0) return INTRO_TIPS[atomId]
        return when {
            st.octaveErrors > st.neighborErrors && st.octaveErrors > 1 ->
                "Right letter, wrong octave. Count the 2-black-key groups: each one starts a new C. " +
                    "The label tells you which octave number to hit."

            st.neighborErrors > 1 ->
                "You're landing one key off. Check the black-key pattern BEFORE pressing: " +
                    "C sits left of 2 black keys, F sits left of 3."

            else -> INTRO_TIPS[atomId]
        }
    }

    companion object {
        const val DRILL_LENGTH = 8
        const val MIN_PROMPTS = 6
        const val MASTERY_ACC = 0.92f
        const val MASTERY_LAT_MS = 2500f

        val ATOM_IDS = listOf(
            "find-C", "find-D", "find-E", "find-F", "find-G", "find-A", "find-B",
            "octaves", "rh-position", "lh-position",
        )

        val LABELS = mapOf(
            "find-C" to "Find C", "find-D" to "Find D", "find-E" to "Find E",
            "find-F" to "Find F", "find-G" to "Find G", "find-A" to "Find A",
            "find-B" to "Find B", "octaves" to "Octave jumps",
            "rh-position" to "Right hand position", "lh-position" to "Left hand position",
        )

        val LETTER_SEMITONE = mapOf(
            "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
        )
        private val WHITE_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val RH_POSITION = intArrayOf(60, 62, 64, 65, 67)
        private val LH_POSITION = intArrayOf(48, 50, 52, 53, 55)

        val INTRO_TIPS = mapOf(
            "find-C" to "Black keys come in groups of 2 and 3. Every C is the white key just LEFT of a 2-group.",
            "find-D" to "D sits BETWEEN the 2 black keys of a 2-group.",
            "find-E" to "E is the white key just RIGHT of a 2-group.",
            "find-F" to "F is the white key just LEFT of a 3-group.",
            "find-G" to "G is inside the 3-group: right of its first black key.",
            "find-A" to "A is inside the 3-group: right of its middle black key.",
            "find-B" to "B is the white key just RIGHT of a 3-group.",
            "octaves" to "The same letter repeats every 12 keys — one octave. The number says which one: C4 is middle C.",
            "rh-position" to "Right thumb (1) on middle C, one finger per key up to G. Press with the finger already on the key.",
            "lh-position" to "Left little finger (5) on the C below middle C, thumb (1) on G. Fingers curved, wrists level.",
        )

        /** Prompts as a wait-mode score, one note every 2s. */
        fun toScore(drill: Drill): Score {
            val notes = drill.prompts.mapIndexed { i, p ->
                ChartNote(
                    midiNote = p.midiNote,
                    startSeconds = i * 2.0,
                    durationSeconds = 1.0,
                    hand = if (p.midiNote < 60) ChartNote.HAND_LEFT else ChartNote.HAND_RIGHT,
                    measure = i + 1,
                )
            }
            return Score(drill.title, notes, 60.0, 4, notes.size * 2.0)
        }
    }
}
