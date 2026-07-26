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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where middle C is on the learner's actual instrument, drawn to scale.
 *
 * His piano is a KORG LP-380U: 88 keys, A0–C8, RH3 weighted action (confirmed
 * from Korg's published specifications). That makes middle C the **24th white
 * key of 52** — just left of centre. "Near the middle" is the usual instruction
 * and it is not good enough for someone who has never sat at a piano; a picture
 * of the whole instrument with one key marked is.
 *
 * Everything here is vector, drawn from the real key layout rather than an
 * illustration, so it matches what he is looking at key for key.
 */

/** Lowest and highest MIDI notes on an 88-key piano. */
private const val LOW = 21 // A0
private const val HIGH = 108 // C8
private const val MIDDLE_C = 60

@Composable
fun KeyboardMap(modifier: Modifier = Modifier, showFingers: Boolean = true) {
    val measurer = rememberTextMeasurer()
    Column(
        modifier = modifier.widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your piano, from above", color = MapAmber)
        Text(
            "All 88 keys. The arrow is middle C — the 24th white key from the left, " +
                "a little left of the middle of the piano.",
            color = MapFg,
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            drawFullKeyboard(measurer)
        }

        if (showFingers) {
            Text("Close up, where your right hand goes", color = MapAmber)
            Text(
                "Thumb on middle C, then one finger per white key going right: 1 2 3 4 5. " +
                    "Keep the fingers curved, as if holding a ball.",
                color = MapFg,
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                drawOctaveCloseUp(measurer)
            }
            Text(
                "Nothing here is scored — the piano only tells the app which key, " +
                    "never which finger.",
                color = MapDim,
            )
        }
    }
}

/** All 88 keys to scale, with middle C called out. */
private fun DrawScope.drawFullKeyboard(measurer: TextMeasurer) {
    var whites = 0
    for (n in LOW..HIGH) if (!isBlack(n)) whites++
    val w = size.width / whites
    val h = size.height * 0.66f
    val top = size.height - h

    var idx = 0
    var middleCx = 0f
    for (n in LOW..HIGH) {
        if (isBlack(n)) continue
        val x = idx * w
        val isMiddle = n == MIDDLE_C
        drawRect(
            color = if (isMiddle) Color(0xFF00E676) else Color(0xFFF0F0EB),
            topLeft = Offset(x + 0.5f, top),
            size = Size(w - 1f, h),
        )
        if (isMiddle) middleCx = x + w / 2f
        idx++
    }
    // Black keys sit between whites, in the real 2-then-3 pattern that the
    // landmark rule depends on.
    idx = 0
    for (n in LOW..HIGH) {
        if (isBlack(n)) {
            drawRect(
                color = Color(0xFF181E24),
                topLeft = Offset(idx * w - w * 0.30f, top),
                size = Size(w * 0.60f, h * 0.62f),
            )
        } else {
            idx++
        }
    }

    // The pointer. One mark, one label.
    val arrowBottom = top - 2f
    drawLine(
        color = Color(0xFF00E676),
        start = Offset(middleCx, arrowBottom - 14f),
        end = Offset(middleCx, arrowBottom),
        strokeWidth = 3f,
    )
    val label = measurer.measure(
        "middle C",
        TextStyle(fontSize = 13.sp, color = Color(0xFF00E676)),
    )
    drawText(
        textLayoutResult = label,
        topLeft = Offset(
            (middleCx - label.size.width / 2f).coerceIn(0f, size.width - label.size.width),
            arrowBottom - 14f - label.size.height,
        ),
    )
}

/**
 * One octave around middle C, large enough to count keys on, with the
 * two-black-key group that locates C highlighted.
 */
private fun DrawScope.drawOctaveCloseUp(measurer: TextMeasurer) {
    val low = 60 // C4
    val high = 72 // C5
    var whites = 0
    for (n in low..high) if (!isBlack(n)) whites++
    val w = size.width / whites
    val h = size.height

    var idx = 0
    for (n in low..high) {
        if (isBlack(n)) continue
        drawRect(Color(0xFFF0F0EB), Offset(idx * w + 1f, 0f), Size(w - 2f, h))
        idx++
    }
    idx = 0
    for (n in low..high) {
        if (isBlack(n)) {
            // The 2-group immediately right of C is what tells you it IS a C.
            val inTwoGroup = (n % 12) == 1 || (n % 12) == 3
            drawRect(
                color = if (inTwoGroup) Color(0xFF4A3A12) else Color(0xFF181E24),
                topLeft = Offset(idx * w - w * 0.29f, 0f),
                size = Size(w * 0.58f, h * 0.62f),
            )
        } else {
            idx++
        }
    }

    // Fingers 1–5 on C D E F G.
    for (finger in 0 until 5) {
        val cx = finger * w + w / 2f
        val cy = h * 0.78f
        drawCircle(
            color = if (finger == 0) Color(0xFF00E676) else Color(0xFF2A7F55),
            radius = w * 0.26f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = Color(0xFF0A140E),
            radius = w * 0.26f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f),
        )
        val laid = measurer.measure(
            "${finger + 1}",
            TextStyle(fontSize = 16.sp, color = Color(0xFF06100A)),
        )
        drawText(
            textLayoutResult = laid,
            topLeft = Offset(cx - laid.size.width / 2f, cy - laid.size.height / 2f),
        )
    }

    // Name the C so the picture and the word are learned together.
    val cLabel = measurer.measure(
        "C",
        TextStyle(fontSize = 15.sp, color = Color(0xFF5A6470)),
    )
    drawText(
        textLayoutResult = cLabel,
        topLeft = Offset(w / 2f - cLabel.size.width / 2f, h - cLabel.size.height - 4f),
    )
}

private fun isBlack(n: Int): Boolean = when (n % 12) {
    1, 3, 6, 8, 10 -> true
    else -> false
}

private val MapFg = Color(0xFFC8D2D7)
private val MapDim = Color(0xFF78828C)
private val MapAmber = Color(0xFFFFB74D)
