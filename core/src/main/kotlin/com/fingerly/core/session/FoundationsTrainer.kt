package com.fingerly.core.session

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import kotlin.random.Random

/**
 * Adaptive foundations trainer (SPEC §8a applied to raw basics).
 *
 * MODULARITY CONTRACT (see docs/LEARNING.md):
 *  - every research-tunable number lives in [Config], injectable and documented;
 *  - trainable skills are [AtomDef] DATA — new drills are added by appending a
 *    definition, never by editing engine logic;
 *  - this engine owns basics acquisition only; song learning is the separate
 *    [SessionEngine] (motor-sequence learning, FSRS). They share measurement
 *    (HitJudge) and the §8a profile, nothing else.
 *
 * ADHD-informed design, from the literature: short predictable drill blocks,
 * an always-visible mastery map, interleaved strong material for success rate,
 * gentle in-the-moment feedback with teaching in between-drill summaries, and
 * suggested stopping points. Mastery = fast AND accurate, measured.
 */
class FoundationsTrainer(
    serialized: String? = null,
    /** All research-tunable parameters in one place — see docs/LEARNING.md. */
    val config: Config = Config(),
    /** Atom definitions are data: add/replace drills without touching engine code. */
    private val atomDefs: List<AtomDef> = defaultAtoms(),
) {

    /** Adjust from evidence (papers or this learner's data), not taste. */
    data class Config(
        val drillLength: Int = 8, // short blocks: ADHD attention literature
        val strongPadCount: Int = 3, // interleaved success padding
        val minPrompts: Int = 6, // data needed before mastery can be claimed
        val masteryAcc: Float = 0.92f, // first-try accuracy for mastery
        val masteryLatMs: Float = 2500f, // fast AND accurate, not just accurate
        val emaAlpha: Float = 0.25f, // recency weight of new evidence
        val testPassPercent: Int = 93, // checkpoint bar
        val testLength: Int = 14,
        val promptSpacingSec: Double = 2.0,
        val sittingDrillCap: Int = 5, // suggest stopping after this many drills
    )

    /** One trainable skill: identity, teaching copy, prompt generator. */
    class AtomDef(
        val id: String,
        val label: String,
        val introTip: String,
        val promptNote: (Random) -> Int,
    )

    class AtomStats(private val config: Config = Config()) {
        var emaAcc = -1f // first-try-correct rate
        var emaLatMs = -1f // time from prompt to correct key
        var n = 0
        var octaveErrors = 0
        var neighborErrors = 0
        var otherErrors = 0

        fun addPrompt(correctFirstTry: Boolean, latencyMs: Int) {
            val a = config.emaAlpha
            val acc = if (correctFirstTry) 1f else 0f
            emaAcc = if (emaAcc < 0) acc else emaAcc * (1 - a) + acc * a
            val lat = latencyMs.coerceAtMost(15_000).toFloat()
            emaLatMs = if (emaLatMs < 0) lat else emaLatMs * (1 - a) + lat * a
            n++
        }

        fun mastered(): Boolean =
            n >= config.minPrompts && emaAcc >= config.masteryAcc &&
                emaLatMs in 0f..config.masteryLatMs

        /** 0..100 for the progress meter: accuracy tempered by speed. */
        fun masteryPercent(): Int {
            if (n == 0) return 0
            val accPart = (emaAcc.coerceIn(0f, 1f)) * 70f
            val latPart = if (emaLatMs <= 0) 0f else
                (1f - ((emaLatMs - config.masteryLatMs) / 6000f).coerceIn(0f, 1f)) * 30f
            return (accPart + latPart).toInt().coerceIn(0, 100)
        }

        fun serialize(): String = "$emaAcc:$emaLatMs:$n:$octaveErrors:$neighborErrors:$otherErrors"

        companion object {
            fun deserialize(s: String, config: Config): AtomStats = AtomStats(config).apply {
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

    class MasteryRow(val id: String, val label: String, val percent: Int, val mastered: Boolean)

    val atoms = LinkedHashMap<String, AtomStats>()
    private val defsById = atomDefs.associateBy { it.id }
    private var totalDrills = 0

    init {
        for (def in atomDefs) atoms[def.id] = AtomStats(config)
        serialized?.split(';')?.forEach { entry ->
            val i = entry.indexOf('=')
            if (i > 0) {
                val id = entry.substring(0, i)
                if (id == "drills") {
                    totalDrills = entry.substring(i + 1).toIntOrNull() ?: 0
                } else if (id in atoms) {
                    atoms[id] = AtomStats.deserialize(entry.substring(i + 1), config)
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
        atomDefs.map { def ->
            val st = atoms.getValue(def.id)
            MasteryRow(def.id, def.label, st.masteryPercent(), st.mastered())
        }

    fun nextDrill(): Drill {
        if (allMastered()) return buildTest()
        val focus = atoms.entries
            .filter { !it.value.mastered() }
            .minByOrNull { it.value.masteryPercent() }!!.key
        val rng = Random(totalDrills * 7919 + 13)
        val prompts = ArrayList<Prompt>(config.drillLength)
        repeat(config.drillLength - config.strongPadCount) {
            prompts.add(promptFor(focus, rng))
        }
        // Pad with strongest material: success moments protect engagement.
        val strong = atoms.entries.sortedByDescending { it.value.masteryPercent() }
            .take(config.strongPadCount).map { it.key }
        repeat(config.strongPadCount) { i ->
            prompts.add(promptFor(strong[i % strong.size], rng))
        }
        prompts.shuffle(rng)
        totalDrills++
        return Drill(
            focusAtom = focus,
            title = defsById.getValue(focus).label,
            tip = tipFor(focus),
            prompts = prompts,
            isTest = false,
        )
    }

    private fun buildTest(): Drill {
        val rng = Random(totalDrills * 104729 + 7)
        val prompts = ArrayList<Prompt>()
        for (def in atomDefs) {
            prompts.add(promptFor(def.id, rng))
            prompts.add(promptFor(def.id, rng))
        }
        prompts.shuffle(rng)
        totalDrills++
        return Drill(
            focusAtom = "test",
            title = "Checkpoint",
            tip = null,
            prompts = prompts.take(config.testLength),
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

    /** Did a checkpoint pass? First-try rate over the test prompts. */
    fun testPassed(results: List<PromptResult>): Boolean {
        if (results.isEmpty()) return false
        val firstTry = results.count { it.correctFirstTry }
        return firstTry * 100 / results.size >= config.testPassPercent
    }

    /** Blunt strengths/weaknesses report (SPEC §2.8). */
    fun report(): String {
        val mastered = atomDefs.filter { atoms.getValue(it.id).mastered() }.map { it.label }
        val weak = atomDefs.filter { !atoms.getValue(it.id).mastered() }
            .sortedBy { atoms.getValue(it.id).masteryPercent() }
        val sb = StringBuilder()
        if (mastered.isNotEmpty()) sb.append("Solid: ${mastered.joinToString(", ")}. ")
        if (weak.isEmpty()) {
            sb.append("All basics measured at mastery — fast and accurate.")
        } else {
            sb.append("Still weak: ")
            sb.append(
                weak.joinToString(", ") {
                    "${it.label} ${atoms.getValue(it.id).masteryPercent()}%"
                },
            )
            sb.append('.')
        }
        return sb.toString()
    }

    private fun promptFor(atomId: String, rng: Random): Prompt =
        Prompt(atomId, defsById.getValue(atomId).promptNote(rng))

    /** Micro-tip chosen by the atom's dominant error type; intro tip when new. */
    private fun tipFor(atomId: String): String? {
        val st = atoms.getValue(atomId)
        val def = defsById.getValue(atomId)
        if (st.n == 0) return def.introTip
        return when {
            st.octaveErrors > st.neighborErrors && st.octaveErrors > 1 ->
                "Right letter, wrong octave. Count the 2-black-key groups: each one starts a new C. " +
                    "The label tells you which octave number to hit."

            st.neighborErrors > 1 ->
                "You're landing one key off. Check the black-key pattern BEFORE pressing: " +
                    "C sits left of 2 black keys, F sits left of 3."

            else -> def.introTip
        }
    }

    companion object {
        // Back-compat aliases for the default config (used by tests/UI copy).
        const val DRILL_LENGTH = 8
        const val MIN_PROMPTS = 6
        const val MASTERY_ACC = 0.92f
        const val MASTERY_LAT_MS = 2500f

        val LETTER_SEMITONE = mapOf(
            "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
        )
        private val RH_POSITION = intArrayOf(60, 62, 64, 65, 67)
        private val LH_POSITION = intArrayOf(48, 50, 52, 53, 55)

        /** The default curriculum. Extend by appending new [AtomDef]s. */
        fun defaultAtoms(): List<AtomDef> {
            val letters = listOf(
                Triple("C", "Black keys come in groups of 2 and 3. Every C is the white key just LEFT of a 2-group.", 0),
                Triple("D", "D sits BETWEEN the 2 black keys of a 2-group.", 2),
                Triple("E", "E is the white key just RIGHT of a 2-group.", 4),
                Triple("F", "F is the white key just LEFT of a 3-group.", 5),
                Triple("G", "G is inside the 3-group: right of its first black key.", 7),
                Triple("A", "A is inside the 3-group: right of its middle black key.", 9),
                Triple("B", "B is the white key just RIGHT of a 3-group.", 11),
            )
            val atoms = ArrayList<AtomDef>()
            for ((letter, tip, semitone) in letters) {
                atoms.add(
                    AtomDef("find-$letter", "Find $letter", tip) { rng ->
                        12 * (rng.nextInt(4) + 2) + 12 + semitone // octaves 2..5
                    },
                )
            }
            atoms.add(
                AtomDef(
                    "octaves", "Octave jumps",
                    "The same letter repeats every 12 keys — one octave. The number says which one: C4 is middle C.",
                ) { rng ->
                    val semis = intArrayOf(0, 2, 4, 5, 7, 9, 11)
                    12 * (rng.nextInt(4) + 2) + 12 + semis[rng.nextInt(semis.size)]
                },
            )
            atoms.add(
                AtomDef(
                    "rh-position", "Right hand position",
                    "Right thumb (1) on middle C, one finger per key up to G. Press with the finger already on the key.",
                ) { rng -> RH_POSITION[rng.nextInt(RH_POSITION.size)] },
            )
            atoms.add(
                AtomDef(
                    "lh-position", "Left hand position",
                    "Left little finger (5) on the C below middle C, thumb (1) on G. Fingers curved, wrists level.",
                ) { rng -> LH_POSITION[rng.nextInt(LH_POSITION.size)] },
            )
            return atoms
        }

        val ATOM_IDS: List<String> get() = defaultAtoms().map { it.id }
        val INTRO_TIPS: Map<String, String>
            get() = defaultAtoms().associate { it.id to it.introTip }

        /** Prompts as a wait-mode score, one note every spacing interval. */
        fun toScore(drill: Drill, spacingSec: Double = 2.0): Score {
            val notes = drill.prompts.mapIndexed { i, p ->
                ChartNote(
                    midiNote = p.midiNote,
                    startSeconds = i * spacingSec,
                    durationSeconds = 1.0,
                    hand = if (p.midiNote < 60) ChartNote.HAND_LEFT else ChartNote.HAND_RIGHT,
                    measure = i + 1,
                )
            }
            return Score(drill.title, notes, 60.0, 4, notes.size * spacingSec)
        }
    }
}
