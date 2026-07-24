package com.keyquest.core.midi

/**
 * Mutable, pooled MIDI event. Instances live in [MidiEventRing] slots and are reused
 * forever — never allocated on the hot path (SPEC §1). Consumers must copy fields out
 * if they need them past the drain callback.
 */
class MidiEvent {
    var type: Int = TYPE_NONE
    var channel: Int = 0

    /** Note number / controller number, depending on [type]. */
    var data1: Int = 0

    /** Velocity / controller value, depending on [type]. */
    var data2: Int = 0

    /** Receive time from System.nanoTime(), stamped on the MIDI delivery thread. */
    var timestampNanos: Long = 0L

    fun set(type: Int, channel: Int, data1: Int, data2: Int, timestampNanos: Long) {
        this.type = type
        this.channel = channel
        this.data1 = data1
        this.data2 = data2
        this.timestampNanos = timestampNanos
    }

    companion object {
        const val TYPE_NONE = 0
        const val TYPE_NOTE_ON = 1
        const val TYPE_NOTE_OFF = 2
        const val TYPE_CONTROL_CHANGE = 3
        const val TYPE_PITCH_BEND = 4
        const val TYPE_OTHER = 5
    }
}
