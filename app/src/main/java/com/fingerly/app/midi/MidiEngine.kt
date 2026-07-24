package com.fingerly.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.fingerly.app.log.RemoteLog
import com.fingerly.core.midi.MidiEventRing
import com.fingerly.core.midi.MidiParser
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Connection status for the UI. Immutable snapshots; not part of the hot path. */
data class MidiConnectionState(
    val connected: Boolean = false,
    val deviceName: String? = null,
)

/**
 * Owns the USB MIDI connection (SPEC §1): auto-connects to the first class-compliant
 * device with an output port, reconnects on plug/unplug, and parses incoming bytes on
 * the MIDI delivery thread straight into the lock-free [ring]. MIDI data never touches
 * the UI thread; consumers drain [ring] from their render loop.
 */
class MidiEngine(context: Context) {

    val ring = MidiEventRing()
    private val parser = MidiParser(ring)

    private val midiManager = context.getSystemService(MidiManager::class.java)

    private val thread = HandlerThread("fingerly-midi", Process.THREAD_PRIORITY_URGENT_AUDIO)
        .apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { r -> handler.post(r) }

    private val _connectionState = MutableStateFlow(MidiConnectionState())
    val connectionState: StateFlow<MidiConnectionState> = _connectionState

    private var openDevice: MidiDevice? = null
    private var openPort: MidiOutputPort? = null
    private var connectedInfo: MidiDeviceInfo? = null

    // Hot path: raw bytes -> parser -> ring, all on the MIDI delivery thread.
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.feed(msg, offset, count, System.nanoTime())
        }
    }

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (openDevice == null) connect(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == connectedInfo?.id) {
                disconnect()
                connectFirstAvailable()
            }
        }
    }

    fun start() {
        midiManager.registerDeviceCallback(
            MidiManager.TRANSPORT_MIDI_BYTE_STREAM, executor, deviceCallback,
        )
        handler.post { connectFirstAvailable() }
    }

    fun stop() {
        midiManager.unregisterDeviceCallback(deviceCallback)
        demoSynth.stop()
        disconnect()
        thread.quitSafely()
    }

    private fun connectFirstAvailable() {
        if (openDevice != null) return
        midiManager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
            .firstOrNull { it.outputPortCount > 0 }
            ?.let { connect(it) }
    }

    private fun connect(info: MidiDeviceInfo) {
        // We read FROM the piano, i.e. from the device's output port.
        if (info.outputPortCount == 0) return
        midiManager.openDevice(info, { device ->
            if (device == null) return@openDevice
            val port = device.openOutputPort(0)
            if (port == null) {
                device.close()
                return@openDevice
            }
            port.connect(receiver)
            openDevice = device
            openPort = port
            connectedInfo = info
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            RemoteLog.log(
                "midi",
                "connected '$name' ports=${info.outputPortCount} id=${info.id}",
            )
            _connectionState.value = MidiConnectionState(connected = true, deviceName = name)
        }, handler)
    }

    /**
     * Demo-only tone synth: audible feedback for the virtual piano. Real USB MIDI
     * never routes through it — the piano makes its own sound (SPEC §1).
     */
    val demoSynth = DemoToneSynth()

    /** Enable while the virtual piano screen is open; disable on leave. */
    fun setDemoSoundEnabled(enabled: Boolean) {
        if (enabled) demoSynth.start() else demoSynth.stop()
    }

    /**
     * Demo mode: inject a synthetic event. Posted to the MIDI thread so the ring's
     * single-producer invariant holds even while a real device is streaming.
     * Not a hot path — allocation here is fine.
     */
    fun injectVirtual(type: Int, note: Int, velocity: Int) {
        handler.post {
            when (type) {
                com.fingerly.core.midi.MidiEvent.TYPE_NOTE_ON -> demoSynth.noteOn(note, velocity)
                com.fingerly.core.midi.MidiEvent.TYPE_NOTE_OFF -> demoSynth.noteOff(note)
            }
            val e = ring.tryClaim() ?: return@post
            e.set(type, 0, note, velocity, System.nanoTime())
            ring.publish()
        }
    }

    private val _demoPlaying = MutableStateFlow(false)
    val demoPlaying: StateFlow<Boolean> = _demoPlaying

    private var demoStep = 0
    private val demoLoop = object : Runnable {
        override fun run() {
            if (!_demoPlaying.value) return
            val note = DEMO_SEQUENCE[demoStep % DEMO_SEQUENCE.size]
            demoSynth.noteOn(note, 96)
            val e1 = ring.tryClaim()
            if (e1 != null) {
                e1.set(com.fingerly.core.midi.MidiEvent.TYPE_NOTE_ON, 0, note, 96, System.nanoTime())
                ring.publish()
            }
            handler.postDelayed({
                demoSynth.noteOff(note)
                val e2 = ring.tryClaim()
                if (e2 != null) {
                    e2.set(com.fingerly.core.midi.MidiEvent.TYPE_NOTE_OFF, 0, note, 0, System.nanoTime())
                    ring.publish()
                }
            }, DEMO_NOTE_MS / 2)
            demoStep++
            handler.postDelayed(this, DEMO_NOTE_MS)
        }
    }

    /** Demo mode: loop a C-major arpeggio through the pipeline, as if played live. */
    fun setDemoPlaying(playing: Boolean) {
        if (_demoPlaying.value == playing) return
        _demoPlaying.value = playing
        if (playing) {
            demoStep = 0
            handler.post(demoLoop)
        }
    }

    private fun disconnect() {
        openPort?.let { port ->
            runCatching { port.disconnect(receiver) }
            runCatching { port.close() }
        }
        runCatching { openDevice?.close() }
        if (connectedInfo != null) {
            RemoteLog.log("midi", "disconnected (dropped events: ${ring.droppedEvents})")
        }
        openPort = null
        openDevice = null
        connectedInfo = null
        _connectionState.value = MidiConnectionState()
    }

    companion object {
        private val DEMO_SEQUENCE = intArrayOf(60, 64, 67, 72, 67, 64) // C E G C G E
        private const val DEMO_NOTE_MS = 400L
    }
}
