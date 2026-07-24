package com.keyquest.core.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiEventRingTest {

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwoCapacity() {
        MidiEventRing(100)
    }

    @Test
    fun eventsComeOutInOrder() {
        val ring = MidiEventRing(8)
        for (note in 1..5) {
            val e = ring.tryClaim()!!
            e.set(MidiEvent.TYPE_NOTE_ON, 0, note, 100, note.toLong())
            ring.publish()
        }
        val notes = mutableListOf<Int>()
        val drained = ring.drain { notes.add(it.data1) }
        assertEquals(5, drained)
        assertEquals(listOf(1, 2, 3, 4, 5), notes)
    }

    @Test
    fun fullRingDropsAndCounts() {
        val ring = MidiEventRing(4)
        repeat(4) {
            assertNotNull(ring.tryClaim())
            ring.publish()
        }
        assertNull(ring.tryClaim())
        assertNull(ring.tryClaim())
        assertEquals(2L, ring.droppedEvents)
        // Draining frees the slots again.
        ring.drain { }
        assertNotNull(ring.tryClaim())
    }

    @Test
    fun drainOnEmptyRingReturnsZero() {
        val ring = MidiEventRing(4)
        assertEquals(0, ring.drain { throw AssertionError("must not be called") })
    }

    @Test
    fun wrapsAroundCorrectly() {
        val ring = MidiEventRing(4)
        var expected = 0
        repeat(10) { round ->
            val e = ring.tryClaim()!!
            e.set(MidiEvent.TYPE_NOTE_ON, 0, round, 0, 0L)
            ring.publish()
            ring.drain { assertEquals(expected++, it.data1) }
        }
        assertEquals(10, expected)
        assertEquals(0L, ring.droppedEvents)
    }

    @Test
    fun spscThreadedSmokeTest() {
        val ring = MidiEventRing(256)
        val total = 100_000
        val received = mutableListOf<Int>()

        val producer = Thread {
            var sent = 0
            while (sent < total) {
                val e = ring.tryClaim() ?: continue // spin when full
                e.set(MidiEvent.TYPE_NOTE_ON, 0, sent and 0x7F, sent, sent.toLong())
                ring.publish()
                sent++
            }
        }
        producer.start()

        var lastSeen = -1L
        var ok = true
        while (received.size < total) {
            ring.drain { e ->
                // Values must arrive in send order with no tearing between fields.
                if (e.timestampNanos != lastSeen + 1) ok = false
                if (e.data1 != (e.data2 and 0x7F)) ok = false
                lastSeen = e.timestampNanos
                received.add(e.data2)
            }
        }
        producer.join(5_000)

        assertEquals(total, received.size)
        assertTrue("events lost, torn or out of order", ok)
        assertEquals(0L, ring.droppedEvents) // producer spun instead of dropping
    }
}
