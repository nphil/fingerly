package com.fingerly.core.latency

/**
 * Rolling latency statistics over the last [windowSize] samples. Fixed storage,
 * no allocation after construction — safe to update from the render loop (SPEC §1).
 */
class LatencyStats(private val windowSize: Int = 256) {

    private val samplesMicros = LongArray(windowSize)
    private var next = 0

    /** Total samples ever recorded (window holds min(totalCount, windowSize)). */
    var totalCount: Long = 0L
        private set

    var lastMicros: Long = 0L
        private set

    fun record(deltaNanos: Long) {
        val micros = deltaNanos / 1_000
        lastMicros = micros
        samplesMicros[next] = micros
        next = (next + 1) % windowSize
        totalCount++
    }

    fun reset() {
        next = 0
        totalCount = 0
        lastMicros = 0
    }

    private inline fun fold(seed: Long, op: (Long, Long) -> Long): Long {
        val n = windowFill()
        if (n == 0) return 0
        var acc = seed
        for (i in 0 until n) acc = op(acc, samplesMicros[i])
        return acc
    }

    fun windowFill(): Int =
        if (totalCount >= windowSize) windowSize else totalCount.toInt()

    fun minMicros(): Long = fold(Long.MAX_VALUE) { a, b -> if (b < a) b else a }

    fun maxMicros(): Long = fold(Long.MIN_VALUE) { a, b -> if (b > a) b else a }

    fun avgMicros(): Long {
        val n = windowFill()
        if (n == 0) return 0
        return fold(0L) { a, b -> a + b } / n
    }

    companion object {
        fun microsToMillis(micros: Long): Float = micros / 1000f
    }
}
