package com.fingerly.app.midi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Minimal polyphonic tone synth for the virtual piano demo ONLY. The real piano
 * produces its own sound and the app must not synthesize audio for live play
 * (SPEC §1) — this exists so demo mode is audible from the tablet speaker.
 * Latency is irrelevant here; never route real USB MIDI through it.
 */
class DemoToneSynth {

    private class Voice(val freqHz: Double, velocity: Int) {
        var phase = 0.0
        var amp = 0.22 * (velocity / 127.0)
        var released = false
    }

    private val voices = HashMap<Int, Voice>() // guarded by synchronized(voices)

    @Volatile
    private var running = false
    private var track: AudioTrack? = null
    private var renderThread: Thread? = null

    fun noteOn(note: Int, velocity: Int) {
        if (!running) return
        val freq = 440.0 * 2.0.pow((note - 69) / 12.0)
        synchronized(voices) { voices[note] = Voice(freq, velocity) }
    }

    fun noteOff(note: Int) {
        synchronized(voices) { voices[note]?.released = true }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, BUFFER_FRAMES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        renderThread = thread(name = "fingerly-demo-synth") {
            val buf = ShortArray(BUFFER_FRAMES)
            while (running) {
                render(buf)
                t.write(buf, 0, buf.size) // blocking write paces the loop
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        renderThread?.join(500)
        renderThread = null
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        synchronized(voices) { voices.clear() }
    }

    private fun render(buf: ShortArray) {
        synchronized(voices) {
            for (i in buf.indices) {
                var s = 0.0
                for (v in voices.values) {
                    // Fundamental + a touch of 2nd harmonic: soft e-piano-ish tone.
                    s += (sin(v.phase) + 0.3 * sin(2 * v.phase)) * v.amp
                    v.phase += TWO_PI * v.freqHz / SAMPLE_RATE
                    v.amp *= if (v.released) RELEASE_PER_SAMPLE else DECAY_PER_SAMPLE
                }
                buf[i] = (s.coerceIn(-1.0, 1.0) * 32767 * 0.6).toInt().toShort()
            }
            voices.values.removeIf { it.amp < 0.0005 }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val BUFFER_FRAMES = 960 // 20ms chunks
        private const val TWO_PI = 2.0 * PI

        // Exponential envelopes: ~0.7s half-life held, ~60ms release.
        private const val DECAY_PER_SAMPLE = 0.9999794
        private const val RELEASE_PER_SAMPLE = 0.99976
    }
}
