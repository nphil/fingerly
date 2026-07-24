package com.fingerly.core.midi

/**
 * Streaming MIDI byte parser. Runs on the MIDI delivery thread; emits into a
 * [MidiEventRing] with zero allocation (SPEC §1). Handles running status, note-on with
 * velocity 0 (= note-off), interleaved real-time bytes, and skips SysEx.
 *
 * Not thread-safe: exactly one thread may call [feed] (the ring's single producer).
 */
class MidiParser(private val ring: MidiEventRing) {

    private var runningStatus = 0
    private var firstDataByte = -1
    private var inSysEx = false

    /** Parse [count] bytes of raw MIDI starting at [offset], stamping [timestampNanos]. */
    fun feed(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            when {
                // Real-time messages (0xF8–0xFF) may interleave anywhere; ignore them
                // without disturbing running status or SysEx state.
                b >= 0xF8 -> {}
                b == 0xF0 -> {
                    inSysEx = true
                    runningStatus = 0
                    firstDataByte = -1
                }
                b == 0xF7 -> inSysEx = false
                b >= 0xF1 -> {
                    // System common: cancels running status; data bytes that follow
                    // are dropped below because runningStatus == 0.
                    inSysEx = false
                    runningStatus = 0
                    firstDataByte = -1
                }
                b >= 0x80 -> {
                    inSysEx = false
                    runningStatus = b
                    firstDataByte = -1
                }
                else -> if (!inSysEx && runningStatus != 0) onDataByte(b, timestampNanos)
            }
            i++
        }
    }

    private fun onDataByte(b: Int, timestampNanos: Long) {
        val isTwoByte = when (runningStatus and 0xF0) {
            0xC0, 0xD0 -> false // program change / channel pressure: 1 data byte
            else -> true
        }
        if (isTwoByte && firstDataByte < 0) {
            firstDataByte = b
            return
        }
        val d1 = if (isTwoByte) firstDataByte else b
        val d2 = if (isTwoByte) b else 0
        firstDataByte = -1
        emit(runningStatus, d1, d2, timestampNanos)
    }

    private fun emit(status: Int, d1: Int, d2: Int, timestampNanos: Long) {
        val event = ring.tryClaim() ?: return
        val type = when (status and 0xF0) {
            0x90 -> if (d2 == 0) MidiEvent.TYPE_NOTE_OFF else MidiEvent.TYPE_NOTE_ON
            0x80 -> MidiEvent.TYPE_NOTE_OFF
            0xB0 -> MidiEvent.TYPE_CONTROL_CHANGE
            0xE0 -> MidiEvent.TYPE_PITCH_BEND
            else -> MidiEvent.TYPE_OTHER
        }
        event.set(type, status and 0x0F, d1, d2, timestampNanos)
        ring.publish()
    }
}
