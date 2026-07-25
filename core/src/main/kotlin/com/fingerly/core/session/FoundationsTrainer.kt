package com.fingerly.core.session

import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import kotlin.random.Random

/**
 * Adaptive foundations trainer: keyboard geography by RETRIEVAL, not recognition.
 *
 * MODULARITY CONTRACT (see docs/LEARNING.md):
 *  - every research-tunable number lives in [Config], injectable and documented;
 *  - trainable skills are [AtomDef] DATA — new drills append a definition;
 *  - this engine owns basics acquisition only. Song learning is [SessionEngine]
 *    (motor sequences, FSRS). Shared: measurement (HitJudge) and the §8a profile.
 *
 * Evidence base (citations in docs/LEARNING.md):
 *  - the prompt names a key and the learner must PRODUCE it with no on-screen
 *    answer (Rowland 2014: recall ≫ recognition, g = 0.50);
 *  - mastery is COUNTED, not estimated: 3 unaided hits in a session, then one
 *    cold hit on each of 3 distinct later days (Rawson & Dunlosky 2011);
 *  - repetitions past the session criterion are refused as empirically inert
 *    (Rohrer & Taylor 2006);
 *  - schedule shape is near-irrelevant once retrieval succeeds, so distinct-days
 *    counting replaces any FSRS-style model here (Karpicke & Bauernschmidt 2011);
 *  - items are kept near-blocked with light spacing, NOT interleaved: for
 *    word-like cue→response pairs interleaving is negative (Brunmair & Richter
 *    2019, g = −0.39);
 *  - difficulty is one visible ladder (how many octaves are in play) clamped to
 *    an ~85% success band (Wilson et al. 2019);
 *  - error history is exponentially decayed, never cumulative (Galyardt &
 *    Goldin 2015).
 */
class FoundationsTrainer(
    serialized: String? = null,
    val config: Config = Config(),
    private val atomDefs: List<AtomDef> = defaultAtoms(),
) {

    /** Adjust from evidence (papers or this learner's logged data), not taste. */
    data class Config(
        val drillLength: Int = 8, // short blocks; effort quantum stated up front
        val padCount: Int = 3, // already-strong items padding each drill
        val sessionCriterionHits: Int = 3, // Rawson & Dunlosky initial-session criterion
        val postCriterionHitsPerDay: Int = 1, // then one per later session
        val criterionDays: Int = 3, // distinct days of cold success for mastery
        val stepUpAt: Float = 0.85f, // Wilson et al. success band
        val stepDownBelow: Float = 0.70f,
        val minPromptsPerRungStep: Int = 4, // never step on a 1-observation denominator
        val maxRung: Int = 2, // 0 = C4 octave, 1 = +below, 2 = +above. C2 is out of reach.
        val revealAfterMs: Long = 4000, // corrective feedback AFTER the attempt
        val forceAdvanceAfterMs: Long = 12_000, // hard escape: no dead ends, ever
        val minGapBetweenSameAtom: Int = 2, // light spacing, not interleaving
        val errorDecay: Float = 0.85f, // recency-weighted error rates
    )

    /** One trainable skill: identity, teaching copy, prompt generator per rung. */
    class AtomDef(
        val id: String,
        val label: String,
        val introTip: String,
        /** (rng, rung) → midi note. Rung widens the octave pool, not the answer space. */
        val promptNote: (Random, Int) -> Int,
    )

    class AtomStats(private val config: Config = Config()) {
        /** Unaided first-try hits banked per rung (index = rung). */
        val hitsAtRung = IntArray(8)

        /** Unaided hits this sitting; resets when a new sitting starts. */
        var todayHits = 0

        /** Distinct days whose FIRST attempt at this atom was unaided-correct. */
        var daysCredited = 0
        var lastCreditedDay = -1
        var lastPromptedDay = -1
        var lastPromptedSeq = 0

        var rung = 0
        var promptsAtRung = 0
        var unaidedAtRung = 0

        // Decayed error rates (Galyardt & Goldin): recent behavior, not lifetime.
        var octaveRate = 0f
        var neighborRate = 0f
        var otherRate = 0f
        var introShown = false

        /** Total prompts ever seen — for honest "no data yet" reporting. */
        var promptsSeen = 0

        /** Unaided hits banked at rung ≥ 1 (evidence beyond the home octave). */
        fun hitsBeyondHomeOctave(): Int =
            (1 until hitsAtRung.size).sumOf { hitsAtRung[it] }

        fun totalHits(): Int = hitsAtRung.sum()

        /** Session criterion: enough unaided hits to count as learned today. */
        fun atCriterion(): Boolean = totalHits() >= config.sessionCriterionHits

        /**
         * Durable mastery: session criterion, evidence outside the home octave,
         * and cold success on separate days.
         */
        fun mastered(): Boolean =
            atCriterion() && hitsBeyondHomeOctave() >= 1 && daysCredited >= config.criterionDays

        /** How many more unaided hits are wanted in this sitting, 0 when done. */
        fun hitsWantedToday(): Int =
            if (atCriterion()) {
                (config.postCriterionHitsPerDay - todayHits).coerceAtLeast(0)
            } else {
                (config.sessionCriterionHits - totalHits()).coerceAtLeast(0)
            }

        fun recordPrompt(unaided: Boolean, dayIndex: Int, seq: Int) {
            promptsSeen++
            promptsAtRung++
            lastPromptedDay = dayIndex
            lastPromptedSeq = seq
            if (unaided) {
                unaidedAtRung++
                hitsAtRung[rung.coerceIn(0, hitsAtRung.size - 1)]++
                todayHits++
            }
        }

        fun recordErrors(octave: Int, neighbor: Int, other: Int) {
            val a = 1f - config.errorDecay
            octaveRate = octaveRate * config.errorDecay + (if (octave > 0) a else 0f)
            neighborRate = neighborRate * config.errorDecay + (if (neighbor > 0) a else 0f)
            otherRate = otherRate * config.errorDecay + (if (other > 0) a else 0f)
        }

        /** Success rate at the current rung; -1 when there is not enough data. */
        fun rungSuccess(): Float =
            if (promptsAtRung < 1) -1f else unaidedAtRung.toFloat() / promptsAtRung

        fun serialize(): String = listOf(
            hitsAtRung.joinToString(","),
            todayHits, daysCredited, lastCreditedDay, lastPromptedDay, lastPromptedSeq,
            rung, promptsAtRung, unaidedAtRung,
            octaveRate, neighborRate, otherRate,
            if (introShown) 1 else 0, promptsSeen,
        ).joinToString(":")

        companion object {
            fun deserialize(s: String, config: Config): AtomStats = AtomStats(config).apply {
                val p = s.split(':')
                if (p.size < 14) return@apply
                p[0].split(',').forEachIndexed { i, v ->
                    if (i < hitsAtRung.size) hitsAtRung[i] = v.toIntOrNull() ?: 0
                }
                todayHits = p[1].toIntOrNull() ?: 0
                daysCredited = p[2].toIntOrNull() ?: 0
                lastCreditedDay = p[3].toIntOrNull() ?: -1
                lastPromptedDay = p[4].toIntOrNull() ?: -1
                lastPromptedSeq = p[5].toIntOrNull() ?: 0
                rung = p[6].toIntOrNull() ?: 0
                promptsAtRung = p[7].toIntOrNull() ?: 0
                unaidedAtRung = p[8].toIntOrNull() ?: 0
                octaveRate = p[9].toFloatOrNull() ?: 0f
                neighborRate = p[10].toFloatOrNull() ?: 0f
                otherRate = p[11].toFloatOrNull() ?: 0f
                introShown = p[12] == "1"
                promptsSeen = p[13].toIntOrNull() ?: 0
            }
        }
    }

    class Prompt(
        val atomId: String,
        val midiNote: Int,
        /** Exactly what is being asked: "any F" at rung 0, "F4" once octaves count. */
        val label: String,
        /**
         * True when any octave of this letter is correct. At the home rung the
         * skill is "which white key is an F" — the octave digit would demand a
         * skill the octaves atom has not taught yet, so asking for it (and
         * grading it) would be asking the learner to guess.
         */
        val matchAnyOctave: Boolean,
    )

    class Drill(
        val focusAtom: String,
        val title: String,
        /** Shown ONCE per atom, ≤3 sentences (SPEC §2.4). Null otherwise. */
        val tip: String?,
        val prompts: List<Prompt>,
    )

    class PromptResult(
        val atomId: String,
        /** Correct on the first press, with no reveal — the only thing that counts. */
        val unaided: Boolean,
        val revealed: Boolean,
        val latencyMs: Int,
        val expectedNote: Int,
        val wrongPresses: List<Int>,
    )

    class MasteryRow(
        val id: String,
        val label: String,
        val hitsToday: Int,
        val hitsWanted: Int,
        val daysCredited: Int,
        val daysWanted: Int,
        val rung: Int,
        val atCriterion: Boolean,
        val mastered: Boolean,
    )

    val atoms = LinkedHashMap<String, AtomStats>()
    private val defsById = atomDefs.associateBy { it.id }
    private var totalDrills = 0
    private var currentDay = -1
    private var seqCounter = 0

    init {
        for (def in atomDefs) atoms[def.id] = AtomStats(config)
        serialized?.split(';')?.forEach { entry ->
            val i = entry.indexOf('=')
            if (i > 0) {
                val id = entry.substring(0, i)
                val value = entry.substring(i + 1)
                when (id) {
                    "drills" -> totalDrills = value.toIntOrNull() ?: 0
                    "day" -> currentDay = value.toIntOrNull() ?: -1
                    "seq" -> seqCounter = value.toIntOrNull() ?: 0
                    else -> if (id in atoms) atoms[id] = AtomStats.deserialize(value, config)
                }
            }
        }
    }

    fun serialize(): String =
        atoms.entries.joinToString(";") { "${it.key}=${it.value.serialize()}" } +
            ";drills=$totalDrills;day=$currentDay;seq=$seqCounter"

    /**
     * Call when a sitting starts. [dayIndex] is days-since-epoch (injected, so
     * this stays a pure testable engine). Rolls the per-sitting hit counters.
     */
    fun startSitting(dayIndex: Int) {
        if (dayIndex != currentDay) {
            currentDay = dayIndex
            for (st in atoms.values) st.todayHits = 0
        }
    }

    fun allMastered(): Boolean = atoms.values.all { it.mastered() }

    /** Atoms that gate the song side — the minimum map needed to read prompts. */
    fun songGateOpen(): Boolean =
        SONG_GATE_ATOMS.all { atoms[it]?.atCriterion() ?: true }

    fun masteryRows(): List<MasteryRow> = atomDefs.map { def ->
        val st = atoms.getValue(def.id)
        MasteryRow(
            id = def.id,
            label = def.label,
            hitsToday = st.todayHits,
            hitsWanted = if (st.atCriterion()) config.postCriterionHitsPerDay else config.sessionCriterionHits,
            daysCredited = st.daysCredited,
            daysWanted = config.criterionDays,
            rung = st.rung,
            atCriterion = st.atCriterion(),
            mastered = st.mastered(),
        )
    }

    /** Atoms still wanting work this sitting. */
    private fun eligibleAtoms(): List<String> =
        atomDefs.map { it.id }.filter { atoms.getValue(it).hitsWantedToday() > 0 }

    /**
     * Build the next drill WITHOUT mutating state (safe to call during compose).
     * Returns null when every atom has met its criterion for this sitting —
     * extra reps past criterion are refused (Rohrer & Taylor).
     */
    fun previewDrill(): Drill? {
        val eligible = eligibleAtoms()
        if (eligible.isEmpty()) return null
        val focus = eligible.minWithOrNull(
            compareBy(
                { atoms.getValue(it).daysCredited },
                { atoms.getValue(it).todayHits },
                { atoms.getValue(it).lastPromptedSeq },
            ),
        )!!
        val rng = Random(totalDrills * 7919 + 13)
        val focusCount = (config.drillLength - config.padCount).coerceAtLeast(1)
        val ids = ArrayList<String>(config.drillLength)
        repeat(focusCount) { ids.add(focus) }
        // Pad with the learner's strongest eligible-or-not material: success moments.
        val pad = atomDefs.map { it.id }
            .filter { it != focus }
            .sortedByDescending { atoms.getValue(it).totalHits() }
            .take(config.padCount.coerceAtLeast(1))
        repeat(config.drillLength - focusCount) { i ->
            if (pad.isNotEmpty()) ids.add(pad[i % pad.size])
        }
        val ordered = spaceOut(ids, config.minGapBetweenSameAtom, rng)
        val prompts = ordered.map { id ->
            val st = atoms.getValue(id)
            val note = defsById.getValue(id).promptNote(rng, st.rung)
            // The octaves atom exists to test octave discrimination, so it always
            // names and requires a specific one.
            val anyOctave = st.rung == 0 && id != ATOM_OCTAVES
            Prompt(id, note, labelFor(note, anyOctave), anyOctave)
        }
        return Drill(
            focusAtom = focus,
            title = defsById.getValue(focus).label,
            tip = tipFor(focus),
            prompts = prompts,
        )
    }

    /** Commit to running [drill]: marks its tip as shown and advances the seed. */
    fun startDrill(drill: Drill) {
        totalDrills++
        if (drill.tip != null) atoms.getValue(drill.focusAtom).introShown = true
    }

    /**
     * Reorder so no atom repeats within [minGap] positions where possible —
     * light spacing to separate repetitions, not interleaving.
     */
    private fun spaceOut(ids: List<String>, minGap: Int, rng: Random): List<String> {
        val remaining = ids.toMutableList()
        remaining.shuffle(rng)
        val out = ArrayList<String>(ids.size)
        while (remaining.isNotEmpty()) {
            val pick = remaining.indexOfFirst { candidate ->
                val from = (out.size - minGap).coerceAtLeast(0)
                (from until out.size).none { out[it] == candidate }
            }
            out.add(remaining.removeAt(if (pick >= 0) pick else 0))
        }
        return out
    }

    /**
     * Feed one drill's measured results. [dayIndex] is days-since-epoch;
     * [firstAttemptOfDayColdProbe] marks results whose prompt was this atom's
     * first attempt today (only those can earn a spaced-day credit).
     */
    fun recordResults(results: List<PromptResult>, dayIndex: Int) {
        startSitting(dayIndex)
        val seenThisDrill = HashSet<String>()
        for (r in results) {
            val st = atoms[r.atomId] ?: continue
            val coldProbe = seenThisDrill.add(r.atomId) && st.lastCreditedDay != dayIndex &&
                st.lastPromptedDay != dayIndex
            seqCounter++
            st.recordPrompt(unaided = r.unaided, dayIndex = dayIndex, seq = seqCounter)
            if (r.unaided && coldProbe) {
                st.daysCredited++
                st.lastCreditedDay = dayIndex
            }
            var octave = 0
            var neighbor = 0
            var other = 0
            for (wrong in r.wrongPresses) {
                when (classifyError(r.expectedNote, wrong)) {
                    ERROR_OCTAVE -> octave++
                    ERROR_NEIGHBOR -> neighbor++
                    else -> other++
                }
            }
            // Always update: a clean prompt is evidence AGAINST a live error
            // pattern, so it must pull the decayed rate down (Galyardt & Goldin).
            st.recordErrors(octave, neighbor, other)
        }
        // Rung ladder: clamp the achieved success rate, on an honest denominator.
        for (id in results.map { it.atomId }.distinct()) {
            val st = atoms.getValue(id)
            if (st.promptsAtRung < config.minPromptsPerRungStep) continue
            val rate = st.rungSuccess()
            if (rate >= config.stepUpAt && st.rung < config.maxRung) {
                st.rung++
                st.promptsAtRung = 0
                st.unaidedAtRung = 0
            } else if (rate in 0f..config.stepDownBelow && st.rung > 0) {
                st.rung--
                st.promptsAtRung = 0
                st.unaidedAtRung = 0
            }
        }
    }

    /** Blunt strengths/weaknesses report (SPEC §2.8). Numbers only. */
    fun report(): String {
        val solid = atomDefs.filter { atoms.getValue(it.id).mastered() }.map { it.label }
        val open = atomDefs.filter { !atoms.getValue(it.id).mastered() }
        val sb = StringBuilder()
        if (solid.isNotEmpty()) sb.append("Mastered: ${solid.joinToString(", ")}. ")
        if (open.isEmpty()) {
            sb.append("All basics at criterion on ${config.criterionDays}+ separate days.")
        } else {
            sb.append("Open: ")
            sb.append(
                open.joinToString(", ") {
                    val st = atoms.getValue(it.id)
                    "${it.label} ${st.totalHits()}/${config.sessionCriterionHits} hits · " +
                        "${st.daysCredited}/${config.criterionDays} days"
                },
            )
            sb.append('.')
        }
        return sb.toString()
    }

    /** Named finish state for a sitting (SPEC §3.5). */
    fun sittingFinishLabel(): String {
        val done = atomDefs.filter { atoms.getValue(it.id).todayHits > 0 }.map { it.label }
        return if (done.isEmpty()) {
            "No keys brought to criterion."
        } else {
            "${done.size} brought to criterion today: ${done.joinToString(", ")}"
        }
    }

    /** Intro tip, once per atom, never repeated (SPEC §2.4 anti-wall-of-text). */
    private fun tipFor(atomId: String): String? {
        val st = atoms.getValue(atomId)
        return if (st.introShown) null else defsById.getValue(atomId).introTip
    }

    /** The prompt states its own grading rule — no guessing what is meant. */
    private fun labelFor(midi: Int, anyOctave: Boolean): String {
        val letter = LETTERS[midi % 12]
        return if (anyOctave) "any $letter" else "$letter${midi / 12 - 1}"
    }

    companion object {
        const val ERROR_OCTAVE = 0
        const val ERROR_NEIGHBOR = 1
        const val ERROR_OTHER = 2

        const val ATOM_OCTAVES = "octaves"

        /** Minimum map to read any prompt; gates the song side only. */
        val SONG_GATE_ATOMS = listOf("find-C", "find-F", "find-G")

        val LETTERS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val LETTER_SEMITONE = mapOf(
            "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
        )

        /** Octave pools by rung: home octave, then below, then above. */
        private val RUNG_BASES = intArrayOf(60, 48, 72)

        /**
         * Right letter / wrong octave, adjacent WHITE key, or neither.
         * White-step distance is what the black-key landmark tips address, so
         * classify on white-key geography, not raw semitones.
         */
        fun classifyError(expected: Int, played: Int): Int {
            val delta = played - expected
            if (delta != 0 && delta % 12 == 0) return ERROR_OCTAVE
            val expWhite = whiteIndexOf(expected)
            val playWhite = whiteIndexOf(played)
            if (expWhite >= 0 && playWhite >= 0 && kotlin.math.abs(playWhite - expWhite) == 1) {
                return ERROR_NEIGHBOR
            }
            if (kotlin.math.abs(delta) <= 2) return ERROR_NEIGHBOR
            return ERROR_OTHER
        }

        /** Index of a white key in the white-key sequence, or -1 for black keys. */
        private fun whiteIndexOf(midi: Int): Int {
            val table = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)
            val within = table[midi % 12]
            if (within < 0) return -1
            return (midi / 12) * 7 + within
        }

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
                    AtomDef("find-$letter", "Find $letter", tip) { rng, rung ->
                        RUNG_BASES[rng.nextInt(rung + 1).coerceIn(0, RUNG_BASES.size - 1)] + semitone
                    },
                )
            }
            atoms.add(
                AtomDef(
                    ATOM_OCTAVES, "Octave numbers",
                    "The same letter repeats every 12 keys. The number says which one: C4 is middle C, C3 is one octave lower.",
                ) { rng, rung ->
                    val semis = intArrayOf(0, 2, 4, 5, 7, 9, 11)
                    // This atom is about octave discrimination, so it always spans
                    // at least two octaves regardless of rung.
                    val base = RUNG_BASES[rng.nextInt((rung + 1).coerceAtLeast(2).coerceAtMost(RUNG_BASES.size))]
                    base + semis[rng.nextInt(semis.size)]
                },
            )
            return atoms
        }

        /**
         * Prompts as a wait-mode score. Spacing is short: in recall mode nothing
         * is drawn between prompts, so long gaps are just dead screen.
         */
        fun toScore(drill: Drill, spacingSec: Double = 0.4): Score {
            val notes = drill.prompts.mapIndexed { i, p ->
                ChartNote(
                    midiNote = p.midiNote,
                    startSeconds = i * spacingSec,
                    durationSeconds = 0.3,
                    hand = if (p.midiNote < 60) ChartNote.HAND_LEFT else ChartNote.HAND_RIGHT,
                    measure = i + 1,
                )
            }
            return Score(drill.title, notes, 60.0, 4, notes.size * spacingSec)
        }
    }
}
