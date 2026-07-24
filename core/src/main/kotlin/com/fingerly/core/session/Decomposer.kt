package com.fingerly.core.session

import com.fingerly.core.song.Score

/**
 * Decomposition engine (SPEC §4): splits a parsed score into 2–8 bar
 * micro-passages and orders them by difficulty.
 *
 * v1 heuristics (skill tagging arrives in Phase 4):
 *  - chunk size [targetBars] bars, last chunk absorbed if it would be < 2 bars;
 *  - difficulty = notes/second + chord density + melodic span, so sparse intro
 *    bars rank easier than dense melody bars.
 */
object Decomposer {

    fun decompose(score: Score, targetBars: Int = 4): List<Passage> {
        if (score.notes.isEmpty()) return emptyList()
        val lastMeasure = score.notes.maxOf { it.measure }

        // Chunk measures.
        val ranges = ArrayList<IntRange>()
        var start = 1
        while (start <= lastMeasure) {
            var end = minOf(start + targetBars - 1, lastMeasure)
            // Don't leave a tail passage under 2 bars.
            if (lastMeasure - end in 1 until 2) end = lastMeasure
            ranges.add(start..end)
            start = end + 1
        }

        val passages = ranges.map { range ->
            val notes = score.notes.filter { it.measure in range }
            Triple(range, notes, difficultyOf(notes, score))
        }

        // Rank by difficulty score (stable: ties keep song order).
        val rankOf = passages.withIndex()
            .sortedBy { it.value.third }
            .withIndex()
            .associate { (rank, indexed) -> indexed.index to rank }

        return passages.mapIndexed { i, (range, notes, _) ->
            Passage(
                id = i,
                startMeasure = range.first,
                endMeasure = range.last,
                notes = notes,
                difficultyRank = rankOf.getValue(i),
                skills = skillsOf(notes),
            )
        }
    }

    /**
     * Skill tags (SPEC §4): which demands this passage makes. Drives per-skill
     * mistake attribution (§8a) and just-in-time drills.
     */
    fun skillsOf(notes: List<com.fingerly.core.song.ChartNote>): Set<String> {
        if (notes.isEmpty()) return emptySet()
        val skills = HashSet<String>(6)

        if (notes.any { BLACK_SEMITONES.contains(it.midiNote % 12) }) skills.add(SKILL_BLACK_KEYS)

        // Per-hand analysis: leaps and chords.
        for (hand in 0..1) {
            val handNotes = notes.filter { it.hand == hand }.sortedBy { it.startSeconds }
            if (handNotes.isEmpty()) continue
            val byStart = handNotes.groupBy { (it.startSeconds * 1000).toLong() }
            if (byStart.values.any { it.size > 1 }) skills.add(SKILL_CHORDS)
            val starts = byStart.keys.sorted()
            for (j in 1 until starts.size) {
                val prev = byStart.getValue(starts[j - 1]).maxOf { it.midiNote }
                val cur = byStart.getValue(starts[j]).minOf { it.midiNote }
                if (Math.abs(cur - prev) > LEAP_SEMITONES) {
                    skills.add(SKILL_LEAPS)
                    break
                }
            }
        }

        // Hand independence: both hands sounding in overlapping time.
        val left = notes.filter { it.hand == com.fingerly.core.song.ChartNote.HAND_LEFT }
        val right = notes.filter { it.hand == com.fingerly.core.song.ChartNote.HAND_RIGHT }
        if (left.isNotEmpty() && right.isNotEmpty()) skills.add(SKILL_HANDS_TOGETHER)

        return skills
    }

    const val SKILL_LEAPS = "leaps"
    const val SKILL_CHORDS = "chords"
    const val SKILL_HANDS_TOGETHER = "hands-together"
    const val SKILL_BLACK_KEYS = "black-keys"
    private const val LEAP_SEMITONES = 5
    private val BLACK_SEMITONES = setOf(1, 3, 6, 8, 10)

    private fun difficultyOf(
        notes: List<com.fingerly.core.song.ChartNote>,
        score: Score,
    ): Double {
        if (notes.isEmpty()) return 0.0
        val t0 = notes.minOf { it.startSeconds }
        val t1 = notes.maxOf { it.startSeconds + it.durationSeconds }
        val span = (t1 - t0).coerceAtLeast(0.001)
        val density = notes.size / span

        // Chord density: fraction of notes sharing a start time with another.
        val byStart = notes.groupBy { (it.startSeconds * 1000).toLong() }
        val chordNotes = byStart.values.filter { it.size > 1 }.sumOf { it.size }
        val chordFraction = chordNotes.toDouble() / notes.size

        val pitchSpan = (notes.maxOf { it.midiNote } - notes.minOf { it.midiNote }) / 12.0
        val bothHands = notes.map { it.hand }.distinct().size > 1

        return density + chordFraction * 2.0 + pitchSpan * 0.5 + (if (bothHands) 1.0 else 0.0)
    }
}
