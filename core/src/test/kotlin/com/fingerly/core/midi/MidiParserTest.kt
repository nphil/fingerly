package com.fingerly.core.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {

    private class Collected {
        val types = mutableListOf<Int>()
        val channels = mutableListOf<Int>()
        val data1 = mutableListOf<Int>()
        val data2 = mutableListOf<Int>()
        val timestamps = mutableListOf<Long>()
    }

    private fun parse(vararg bytes: Int, timestamp: Long = 42L): Collected {
        val ring = MidiEventRing(64)
        val parser = MidiParser(ring)
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        parser.feed(data, 0, data.size, timestamp)
        val out = Collected()
        ring.drain { e ->
            out.types.add(e.type)
            out.channels.add(e.channel)
            out.data1.add(e.data1)
            out.data2.add(e.data2)
            out.timestamps.add(e.timestampNanos)
        }
        return out
    }

    @Test
    fun noteOnAndOff() {
        val out = parse(0x90, 60, 100, 0x80, 60, 0)
        assertEquals(listOf(MidiEvent.TYPE_NOTE_ON, MidiEvent.TYPE_NOTE_OFF), out.types)
        assertEquals(listOf(60, 60), out.data1)
        assertEquals(listOf(100, 0), out.data2)
        assertEquals(listOf(42L, 42L), out.timestamps)
    }

    @Test
    fun noteOnVelocityZeroIsNoteOff() {
        val out = parse(0x90, 64, 0)
        assertEquals(listOf(MidiEvent.TYPE_NOTE_OFF), out.types)
        assertEquals(listOf(64), out.data1)
    }

    @Test
    fun runningStatusEmitsMultipleEvents() {
        // One status byte, three note-on pairs via running status.
        val out = parse(0x90, 60, 100, 64, 101, 67, 102)
        assertEquals(3, out.types.size)
        assertEquals(listOf(60, 64, 67), out.data1)
        assertEquals(listOf(100, 101, 102), out.data2)
        assertEquals(
            listOf(MidiEvent.TYPE_NOTE_ON, MidiEvent.TYPE_NOTE_ON, MidiEvent.TYPE_NOTE_ON),
            out.types,
        )
    }

    @Test
    fun channelIsExtracted() {
        val out = parse(0x93, 60, 100)
        assertEquals(listOf(3), out.channels)
    }

    @Test
    fun controlChangeAndPitchBend() {
        val out = parse(0xB0, 64, 127, 0xE0, 0x00, 0x40)
        assertEquals(
            listOf(MidiEvent.TYPE_CONTROL_CHANGE, MidiEvent.TYPE_PITCH_BEND),
            out.types,
        )
        assertEquals(listOf(64, 0x00), out.data1)
        assertEquals(listOf(127, 0x40), out.data2)
    }

    @Test
    fun oneByteMessagesDoNotDesync() {
        // Program change (0xC0, one data byte) followed by a note-on must parse cleanly.
        val out = parse(0xC0, 5, 0x90, 60, 100)
        assertEquals(listOf(MidiEvent.TYPE_OTHER, MidiEvent.TYPE_NOTE_ON), out.types)
        assertEquals(listOf(5, 60), out.data1)
    }

    @Test
    fun sysExIsSkipped() {
        val out = parse(0xF0, 0x7E, 0x01, 0x02, 0xF7, 0x90, 60, 100)
        assertEquals(listOf(MidiEvent.TYPE_NOTE_ON), out.types)
    }

    @Test
    fun realTimeBytesInterleavedInsideMessageAreIgnored() {
        // 0xF8 (clock) injected between status and data bytes must not corrupt parsing.
        val out = parse(0x90, 0xF8, 60, 0xF8, 100)
        assertEquals(listOf(MidiEvent.TYPE_NOTE_ON), out.types)
        assertEquals(listOf(60), out.data1)
        assertEquals(listOf(100), out.data2)
    }

    @Test
    fun strayDataBytesWithoutStatusAreDropped() {
        val out = parse(60, 100, 0x90, 62, 90)
        assertEquals(listOf(MidiEvent.TYPE_NOTE_ON), out.types)
        assertEquals(listOf(62), out.data1)
    }

    @Test
    fun feedRespectsOffsetAndCount() {
        val ring = MidiEventRing(16)
        val parser = MidiParser(ring)
        val data = byteArrayOf(0x00, 0x90.toByte(), 60, 100, 0x00)
        parser.feed(data, 1, 3, 7L)
        var count = 0
        ring.drain { e ->
            count++
            assertEquals(MidiEvent.TYPE_NOTE_ON, e.type)
            assertEquals(7L, e.timestampNanos)
        }
        assertEquals(1, count)
    }

    @Test
    fun messageSplitAcrossFeedCallsIsReassembled() {
        val ring = MidiEventRing(16)
        val parser = MidiParser(ring)
        parser.feed(byteArrayOf(0x90.toByte(), 60), 0, 2, 1L)
        parser.feed(byteArrayOf(100), 0, 1, 2L)
        var count = 0
        ring.drain { e ->
            count++
            assertEquals(MidiEvent.TYPE_NOTE_ON, e.type)
            assertEquals(60, e.data1)
            assertEquals(100, e.data2)
        }
        assertEquals(1, count)
    }
}
