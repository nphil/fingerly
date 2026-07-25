package com.fingerly.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Minimal binary format for attempt MIDI recordings (SPEC §3): a count, then
 * per event [deltaMs int32][type int8][note int8][velocity int8]. Times are
 * song-relative ms so playback aligns with the chart.
 */
object RecordingCodec {

    class Event(val timeMs: Int, val type: Int, val note: Int, val velocity: Int)

    fun encode(count: Int, timesMs: IntArray, types: ByteArray, notes: ByteArray, vels: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(4 + count * 7)
        DataOutputStream(out).use { d ->
            d.writeInt(count)
            for (i in 0 until count) {
                d.writeInt(timesMs[i])
                d.writeByte(types[i].toInt())
                d.writeByte(notes[i].toInt())
                d.writeByte(vels[i].toInt())
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Event> {
        val d = java.io.DataInputStream(bytes.inputStream())
        val count = d.readInt()
        val events = ArrayList<Event>(count)
        repeat(count) {
            events.add(
                Event(
                    timeMs = d.readInt(),
                    type = d.readByte().toInt(),
                    note = d.readByte().toInt() and 0x7F,
                    velocity = d.readByte().toInt() and 0x7F,
                ),
            )
        }
        return events
    }
}
