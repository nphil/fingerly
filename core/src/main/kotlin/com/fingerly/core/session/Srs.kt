package com.fingerly.core.session

/**
 * v1 spaced-repetition scheduler (SPEC §3: SM-2 acceptable for v1; FSRS in
 * Phase 4). The grade is measured accuracy — never self-report.
 */
object Srs {

    /** Review outcome of one passage attempt at (near-)full difficulty. */
    fun nextIntervalDays(previousIntervalDays: Double, accuracyPercent: Float): Double = when {
        accuracyPercent < 70f -> 0.25 // failed: resurface same/next session
        accuracyPercent < AutoDifficulty.CLEAN_AT -> // shaky: shrink interval
            (previousIntervalDays * 0.6).coerceAtLeast(0.25)
        previousIntervalDays <= 0.0 -> 0.5 // first clean pass
        else -> (previousIntervalDays * 2.2).coerceAtMost(30.0)
    }

    fun dueAtMs(nowMs: Long, intervalDays: Double): Long =
        nowMs + (intervalDays * 24 * 60 * 60 * 1000).toLong()
}
