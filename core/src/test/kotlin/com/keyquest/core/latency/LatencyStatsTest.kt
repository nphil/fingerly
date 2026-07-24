package com.keyquest.core.latency

import org.junit.Assert.assertEquals
import org.junit.Test

class LatencyStatsTest {

    @Test
    fun emptyStatsAreZero() {
        val stats = LatencyStats(4)
        assertEquals(0, stats.windowFill())
        assertEquals(0L, stats.minMicros())
        assertEquals(0L, stats.maxMicros())
        assertEquals(0L, stats.avgMicros())
    }

    @Test
    fun recordsMinMaxAvgLast() {
        val stats = LatencyStats(8)
        stats.record(2_000_000) // 2ms
        stats.record(4_000_000) // 4ms
        stats.record(6_000_000) // 6ms
        assertEquals(3, stats.windowFill())
        assertEquals(2_000L, stats.minMicros())
        assertEquals(6_000L, stats.maxMicros())
        assertEquals(4_000L, stats.avgMicros())
        assertEquals(6_000L, stats.lastMicros)
        assertEquals(3L, stats.totalCount)
    }

    @Test
    fun windowEvictsOldestSamples() {
        val stats = LatencyStats(2)
        stats.record(10_000_000)
        stats.record(2_000_000)
        stats.record(4_000_000) // overwrites the 10ms sample
        assertEquals(2, stats.windowFill())
        assertEquals(2_000L, stats.minMicros())
        assertEquals(4_000L, stats.maxMicros())
        assertEquals(3L, stats.totalCount)
    }

    @Test
    fun resetClearsEverything() {
        val stats = LatencyStats(4)
        stats.record(5_000_000)
        stats.reset()
        assertEquals(0, stats.windowFill())
        assertEquals(0L, stats.lastMicros)
        assertEquals(0L, stats.totalCount)
    }

    @Test
    fun microsToMillisConversion() {
        assertEquals(6.9f, LatencyStats.microsToMillis(6_900), 0.001f)
    }
}
