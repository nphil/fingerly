package com.fingerly.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where your hands go, drawn rather than described (SPEC §2.4: anything needing
 * more than three sentences becomes a picture).
 *
 * It carries NO checkmark and nothing scores it. MIDI transmits pitch, not
 * fingers, so "thumb on middle C" cannot be verified — and certifying an
 * unobservable skill is exactly what got the fingering atoms deleted. This is
 * reference, permanently available, never a gate and never a task.
 *
 * The instruction itself is exact rather than vague (SPEC §2.5): a numbered
 * finger on a named key, not "find a comfortable position".
 */
@Composable
fun HandPositionCard(modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Column(
        modifier = modifier.widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Where your hands go", color = HandAmber)
        Text(
            "Right thumb on middle C — the white key left of the two black keys, " +
                "nearest the middle. One finger per white key going up: 1 2 3 4 5.",
            color = HandFg,
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            drawCPosition(measurer)
        }
        Text(
            "Left hand mirrors it an octave down, little finger on the C below. " +
                "Nothing here is scored — the piano cannot tell the app which finger you used.",
            color = HandDim,
        )
    }
}

/** Two octaves of white keys with the right hand's five fingers numbered. */
private fun DrawScope.drawCPosition(measurer: TextMeasurer) {
    val whiteCount = 10 // C3 up to E4-ish: enough context to locate middle C
    val w = size.width / whiteCount
    val h = size.height

    // White keys.
    for (i in 0 until whiteCount) {
        drawRect(
            color = Color(0xFFF0F0EB),
            topLeft = Offset(i * w + 1f, 0f),
            size = Size(w - 2f, h),
        )
    }
    // Black keys, positioned by the real 2-group / 3-group pattern so the
    // landmark rule the drills teach is the same shape seen here.
    val blackAfter = setOf(0, 1, 3, 4, 5) // within each 7-key octave
    for (i in 0 until whiteCount - 1) {
        if ((i % 7) !in blackAfter) continue
        drawRect(
            color = Color(0xFF181E24),
            topLeft = Offset((i + 1) * w - w * 0.29f, 0f),
            size = Size(w * 0.58f, h * 0.62f),
        )
    }

    // Middle C is the 8th white key here (one octave up from the left edge).
    val middleC = 7
    for (finger in 0 until 5) {
        val key = middleC + finger
        if (key >= whiteCount) break
        val cx = key * w + w / 2f
        val cy = h * 0.80f
        drawCircle(
            color = if (finger == 0) Color(0xFF00E676) else Color(0xFF2A7F55),
            radius = w * 0.30f,
            center = Offset(cx, cy),
        )
        val label = (finger + 1).toString()
        val laid = measurer.measure(label, TextStyle(fontSize = 15.sp, color = Color(0xFF06100A)))
        drawText(
            textLayoutResult = laid,
            topLeft = Offset(cx - laid.size.width / 2f, cy - laid.size.height / 2f),
        )
    }
}

private val HandAmber = Color(0xFFFFB74D)
private val HandFg = Color(0xFFC8D2D7)
private val HandDim = Color(0xFF78828C)
