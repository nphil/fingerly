package com.keyquest.app.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.keyquest.core.midi.MidiEventRing
import com.keyquest.core.midi.MidiParser
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

    private val thread = HandlerThread("keyquest-midi", Process.THREAD_PRIORITY_URGENT_AUDIO)
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
            _connectionState.value = MidiConnectionState(
                connected = true,
                deviceName = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                    ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            )
        }, handler)
    }

    private fun disconnect() {
        openPort?.let { port ->
            runCatching { port.disconnect(receiver) }
            runCatching { port.close() }
        }
        runCatching { openDevice?.close() }
        openPort = null
        openDevice = null
        connectedInfo = null
        _connectionState.value = MidiConnectionState()
    }
}
