package com.fingerly.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.fingerly.app.highway.StaffRenderer
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.midi.MidiEvent
import com.fingerly.core.notation.Staff

/**
 * The first two minutes, for someone who has never touched a piano.
 *
 * The module's governing rule is that every drill is the real task with one axis
 * simplified — but the real task assumes a mapping the learner does not have
 * yet: *this shape on the page* ↔ *this key under my hand*. Nothing verifies
 * that mapping anywhere, and a beginner who never establishes it will read the
 * drills as arbitrary.
 *
 * So: four steps, each ending in a MIDI press the app can actually check. It is
 * guided rather than tested — the answer is on screen until the last step of
 * each pair — and it earns nothing, because it is orientation, not learning.
 * Once through, it never appears again unless asked for.
 */
@Composable
fun OrientationScreen(engine: MidiEngine, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var lastPressed by remember { mutableStateOf(-1) }
    var wrongCount by remember { mutableStateOf(0) }
    val midi by engine.connectionState.collectAsState()

    val steps = remember { ORIENTATION_STEPS }
    val current = steps.getOrNull(step)

    // The ring is single-consumer and no highway is mounted here, so this screen
    // owns it for as long as it is up.
    LaunchedEffect(step) {
        if (current == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            var hit = false
            engine.ring.drain { e ->
                if (e.type == MidiEvent.TYPE_NOTE_ON) {
                    lastPressed = e.data1
                    if (e.data1 == current.target) hit = true else wrongCount++
                }
            }
            if (hit) {
                RemoteLog.log("orientation", "step ${step + 1}/${steps.size} ok wrong=$wrongCount")
                step++
                return@LaunchedEffect
            }
        }
    }

    if (current == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember { StaffRenderer(ctx) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Step ${step + 1} of ${steps.size}",
                color = OriDim,
            )
            Text(current.title, style = MaterialTheme.typography.headlineMedium)
            Text(current.detail, color = OriFg, modifier = Modifier.widthIn(max = 700.dp))

            if (current.showHands) KeyboardMap()

            if (current.showStaff) {
                Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    drawIntoCanvas { c ->
                        renderer.draw(
                            canvas = c.nativeCanvas,
                            midi = current.target,
                            clef = current.clef,
                            centerX = size.width * 0.5f,
                            midiCenterY = size.height * 0.5f,
                            staffSpace = size.height * 0.085f,
                            staffWidth = size.width * 0.42f,
                        )
                    }
                }
            }

            Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                drawMiniKeyboard(
                    target = if (current.lightTheKey) current.target else -1,
                    pressed = lastPressed,
                )
            }

            if (!midi.connected) {
                // Every step here is verified by a real press, so with no piano
                // attached the learner would sit on step one with no way to know why.
                Text(
                    "Piano not connected — plug it in over USB-C and this will move on " +
                        "by itself when you press a key.",
                    color = OriAmber,
                    modifier = Modifier.widthIn(max = 700.dp),
                )
            }
            if (lastPressed >= 0 && lastPressed != current.target) {
                Text(
                    "That was ${nameOf(lastPressed)}. Looking for ${nameOf(current.target)}.",
                    color = OriAmber,
                )
            }
            OutlinedButton(onClick = onDone) { Text("Skip setup") }
        }
    }
}

private class OrientationStep(
    val title: String,
    val detail: String,
    val target: Int,
    val clef: Int = Staff.CLEF_TREBLE,
    val showHands: Boolean = false,
    val showStaff: Boolean = false,
    /** Marks the key on the drawn keyboard. Off for the step that checks recall. */
    val lightTheKey: Boolean = true,
)

private val ORIENTATION_STEPS = listOf(
    OrientationStep(
        title = "Find middle C",
        detail = "It is marked below. Look for the group of TWO black keys nearest " +
            "the middle of your piano — middle C is the white key just left of them. " +
            "Press it.",
        target = 60,
        showHands = true,
    ),
    OrientationStep(
        title = "That key, written down",
        detail = "This is the same note on paper: the short line of its own, between " +
            "the two staves. Press middle C again.",
        target = 60,
        showStaff = true,
    ),
    OrientationStep(
        title = "Now without the marker",
        detail = "Same note, nothing highlighted. Find it yourself.",
        target = 60,
        showStaff = true,
        lightTheKey = false,
    ),
    OrientationStep(
        title = "One more landmark",
        detail = "The curl of the treble clef wraps around this line. It is G — four " +
            "white keys above middle C. Press it.",
        target = 67,
        showStaff = true,
    ),
    OrientationStep(
        title = "And the low one",
        detail = "The two dots of the bass clef hug this line. It is F — four white " +
            "keys below middle C. Use your left hand.",
        target = 53,
        clef = Staff.CLEF_BASS,
        showStaff = true,
    ),
)

/** Two octaves either side of middle C, enough to locate it without scrolling. */
private fun DrawScope.drawMiniKeyboard(target: Int, pressed: Int) {
    val low = 48 // C3
    val high = 72 // C5
    var whites = 0
    for (n in low..high) if (!isBlackKey(n)) whites++
    val w = size.width / whites
    val h = size.height

    var idx = 0
    for (n in low..high) {
        if (isBlackKey(n)) continue
        val color = when {
            n == target -> Color(0xFF00E676)
            n == pressed -> Color(0xFF64C4FF)
            else -> Color(0xFFF0F0EB)
        }
        drawRect(color, Offset(idx * w + 1f, 0f), Size(w - 2f, h))
        idx++
    }
    idx = 0
    for (n in low..high) {
        if (isBlackKey(n)) {
            val color = if (n == pressed) Color(0xFF64C4FF) else Color(0xFF181E24)
            drawRect(color, Offset(idx * w - w * 0.29f, 0f), Size(w * 0.58f, h * 0.62f))
        } else {
            idx++
        }
    }
}

private fun isBlackKey(n: Int): Boolean = when (n % 12) {
    1, 3, 6, 8, 10 -> true
    else -> false
}

private val LETTER_NAMES =
    arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun nameOf(midi: Int): String = "${LETTER_NAMES[midi % 12]}${midi / 12 - 1}"

const val PREF_ORIENTATION_DONE = "orientation_done"

private val OriFg = Color(0xFFC8D2D7)
private val OriDim = Color(0xFF78828C)
private val OriAmber = Color(0xFFFFB74D)
