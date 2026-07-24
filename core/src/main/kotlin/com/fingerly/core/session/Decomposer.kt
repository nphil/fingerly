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
            )
        }
    }

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
