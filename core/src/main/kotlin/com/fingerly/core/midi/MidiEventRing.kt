package com.fingerly.core.midi

import java.util.concurrent.atomic.AtomicLong

/** Callback for [MidiEventRing.drain]. Must not retain the event past the call. */
fun interface MidiEventHandler {
    fun onEvent(event: MidiEvent)
}

/**
 * Single-producer / single-consumer lock-free ring of pre-allocated [MidiEvent]s.
 *
 * Producer = the MIDI delivery thread ([MidiParser]); consumer = the render loop.
 * Zero allocation after construction (SPEC §1). Memory ordering: the producer fills a
 * slot then publishes with a release store of `head`; the consumer's acquire load of
 * `head` makes the slot contents visible. The reverse holds for `tail`, so a slot is
 * never overwritten while the consumer may still touch it.
 *
 * If the ring is full the event is dropped and counted — the MIDI thread must never
 * block or allocate.
 */
class MidiEventRing(capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) {
            "capacity must be a power of two, got $capacity"
        }
    }

    private val mask = (capacity - 1).toLong()
    private val slots = Array(capacity) { MidiEvent() }
    private val head = AtomicLong(0) // next slot to write; written only by producer
    private val tail = AtomicLong(0) // next slot to read; written only by consumer

    /** Events dropped because the ring was full. Producer thread only. */
    @Volatile
    var droppedEvents: Long = 0L
        private set

    val capacity: Int get() = slots.size

    /**
     * Producer: claim the next slot to fill, or null if the ring is full (the event
     * is counted as dropped). After filling the slot, call [publish].
     */
    fun tryClaim(): MidiEvent? {
        val h = head.get()
        if (h - tail.get() >= slots.size) {
            droppedEvents++
            return null
        }
        return slots[(h and mask).toInt()]
    }

    /** Producer: make the slot claimed by the last [tryClaim] visible to the consumer. */
    fun publish() {
        head.lazySet(head.get() + 1)
    }

    /** Consumer: process every pending event in order. Returns the number processed. */
    fun drain(handler: MidiEventHandler): Int {
        var t = tail.get()
        val h = head.get()
        var n = 0
        while (t < h) {
            handler.onEvent(slots[(t and mask).toInt()])
            t++
            n++
        }
        if (n > 0) tail.lazySet(t)
        return n
    }

    companion object {
        const val DEFAULT_CAPACITY = 1024
    }
}
