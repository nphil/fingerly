package com.fingerly.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.midi.MidiEvent

/**
 * Demo mode: an on-screen keyboard whose events flow through the exact same
 * MIDI-thread → lock-free ring pipeline as a real USB piano, so every downstream
 * feature can be exercised without hardware. Also hosts the auto-play loop.
 */
@Composable
fun VirtualPianoScreen(engine: MidiEngine, onBack: () -> Unit) {
    val demoPlaying by engine.demoPlaying.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(top = 72.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Virtual piano — same event pipeline as USB MIDI", color = Color(0xFF78828C))
                OutlinedButton(onClick = { engine.setDemoPlaying(!demoPlaying) }) {
                    Text(if (demoPlaying) "Stop auto-play" else "Auto-play arpeggio")
                }
            }
            PianoKeyboard(
                lowNote = 48, // C3
                octaves = 2,
                onDown = { note -> engine.injectVirtual(MidiEvent.TYPE_NOTE_ON, note, 96) },
                onUp = { note -> engine.injectVirtual(MidiEvent.TYPE_NOTE_OFF, note, 0) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        BackText(onBack)
    }
}

// Semitone offsets within an octave that are black keys, keyed to white-key gaps.
private val WHITE_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_AFTER_WHITE = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)

@Composable
private fun PianoKeyboard(
    lowNote: Int,
    octaves: Int,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val whiteCount = octaves * 7 + 1
    BoxWithConstraints(modifier) {
        val whiteWidth: Dp = maxWidth / whiteCount
        val blackWidth = whiteWidth * 0.6f
        val blackHeight = maxHeight * 0.6f

        Row(Modifier.fillMaxSize()) {
            repeat(whiteCount) { i ->
                val note = lowNote + (i / 7) * 12 + WHITE_SEMITONES[i % 7]
                PianoKey(
                    note = note,
                    isBlack = false,
                    onDown = onDown,
                    onUp = onUp,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp),
                )
            }
        }
        repeat(whiteCount - 1) { i ->
            val blackSemitone = BLACK_AFTER_WHITE[i % 7] ?: return@repeat
            val note = lowNote + (i / 7) * 12 + blackSemitone
            PianoKey(
                note = note,
                isBlack = true,
                onDown = onDown,
                onUp = onUp,
                modifier = Modifier
                    .offset(x = whiteWidth * (i + 1) - blackWidth / 2)
                    .size(width = blackWidth, height = blackHeight),
            )
        }
    }
}

@Composable
private fun PianoKey(
    note: Int,
    isBlack: Boolean,
    onDown: (Int) -> Unit,
    onUp: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val color = when {
        pressed -> Color(0xFF00E676)
        isBlack -> Color(0xFF1A2026)
        else -> Color(0xFFF5F5F0)
    }
    Box(
        modifier
            .background(color, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
            .pointerInput(note) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onDown(note)
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                            onUp(note)
                        }
                    },
                )
            },
    )
}
