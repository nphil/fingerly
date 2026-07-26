package com.fingerly.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fingerly.app.highway.StaffRenderer
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.midi.MidiEvent
import com.fingerly.core.notation.KeyGeography
import com.fingerly.core.notation.Staff

/**
 * The first run, for someone who has never touched a piano.
 *
 * Replaces the previous five-step orientation, which taught by restudy (here is
 * middle C, now press middle C), used note names the learner had never been
 * given ("that was D#4"), advanced under his hands on every press, and hard
 * locked if he tapped Skip.
 *
 * Structure comes from a design pass over the adult-beginner method literature
 * reconciled against this project's ADHD record. The shape that survived:
 * **the keyboard is learned by its black-key grouping before any letter exists**,
 * because the grouping is a visual pattern the learner can verify himself, while
 * a letter name is a fact he can only be told. Every idea gets at least three
 * reps, no screen introduces more than one new idea, and the run ends by reading
 * three marks off the same chart the rest of the app uses.
 *
 * Global rules, each fixing something specific:
 *  - **Keys play, taps navigate.** A correct press freezes the screen and shows
 *    Next; nothing ever advances on a press. The screen must not move under his
 *    hands while he is exploring.
 *  - **Wrong presses are reported as a distance in white keys, never as a name.**
 *    "3 white keys left" is countable by someone who knows no letters.
 *  - **Show me is manual and two-stage** — decompose first (redraw the rule),
 *    reveal second. Never on a timer: a timed reveal makes waiting free, and a
 *    tap costs an action and is logged.
 *  - **Stop for now on every screen**, resuming at the start of the last
 *    completed block rather than mid-block.
 *  - Nothing here is scored, and that is said exactly once.
 */
@Composable
fun FirstRunScreen(engine: MidiEngine, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("fingerly", 0) }
    val measurer = rememberTextMeasurer()
    val renderer = remember { StaffRenderer(ctx) }

    var index by remember { mutableStateOf(prefs.getInt(PREF_FIRST_RUN_STEP, 0)) }
    val step = FIRST_RUN.getOrNull(index)

    val pressed = remember { mutableStateListOfInts() }
    var lastWrong by remember { mutableStateOf(-1) }
    var revealStage by remember { mutableStateOf(0) }
    var satisfied by remember { mutableStateOf(false) }
    var everHeardAnything by remember { mutableStateOf(false) }
    var shownCount by remember { mutableStateOf(0) }
    var pressCount by remember { mutableStateOf(0) }

    // Reset per step. The previous screen kept `lastPressed` across steps, so a
    // new step opened already displaying an error the learner had not made.
    LaunchedEffect(index) {
        pressed.clear()
        lastWrong = -1
        revealStage = 0
        satisfied = false
        engine.ring.drain { } // discard anything queued while the last step froze
        if (step == null) return@LaunchedEffect
        var lastAcceptNanos = 0L
        while (true) {
            withFrameNanos { }
            engine.ring.drain { e ->
                if (e.type != MidiEvent.TYPE_NOTE_ON) return@drain
                everHeardAnything = true
                if (satisfied) return@drain
                // Debounce: a bounced key must not count as two presses.
                if (e.timestampNanos - lastAcceptNanos < 30_000_000L) return@drain
                lastAcceptNanos = e.timestampNanos
                pressCount++
                val note = e.data1
                if (step.accepts(note, pressed.toList())) {
                    pressed.add(note)
                    lastWrong = -1
                    if (step.complete(pressed.toList())) satisfied = true
                } else {
                    lastWrong = note
                }
            }
        }
    }

    if (step == null) {
        LaunchedEffect(Unit) {
            prefs.edit().putInt(PREF_FIRST_RUN_STEP, 0)
                .putBoolean(PREF_FIRST_RUN_DONE, true).apply()
            onFinished()
        }
        return
    }

    fun advance() {
        val next = index + 1
        // Resume lands at the start of a block, never mid-block.
        prefs.edit().putInt(PREF_FIRST_RUN_STEP, blockStartFor(next)).apply()
        RemoteLog.log(
            "firstrun",
            "step ${index + 1}/${FIRST_RUN.size} '${step.id}' presses=$pressCount " +
                "reveal=$revealStage",
        )
        if (revealStage > 0) shownCount++
        index = next
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Step ${index + 1} of ${FIRST_RUN.size}", color = RunDim)
                OutlinedButton(onClick = onFinished) { Text("Stop for now") }
            }

            Text(step.title, style = MaterialTheme.typography.headlineMedium)
            Text(step.says, color = RunFg, modifier = Modifier.widthIn(max = 820.dp))

            when (step.visual) {
                Visual.BOARD -> Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    drawBoard(measurer, step, pressed.toList(), lastWrong, revealStage)
                }
                Visual.HAND -> Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                    drawHandOctave(measurer, pressed.toList())
                }
                Visual.CHART -> Column {
                    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
                        drawIntoCanvas { c ->
                            if (step.marks.size == 1) {
                                renderer.draw(
                                    c.nativeCanvas, step.marks[0], Staff.CLEF_TREBLE,
                                    size.width * 0.5f, size.height * 0.5f,
                                    size.height * 0.085f, size.width * 0.40f,
                                )
                            } else {
                                val n = step.marks.size
                                renderer.drawExcerpt(
                                    canvas = c.nativeCanvas,
                                    midi = step.marks, startBeats = DoubleArray(n) { it * 1.0 },
                                    durationBeats = DoubleArray(n) { 1.0 },
                                    clefs = ByteArray(n) { Staff.CLEF_TREBLE.toByte() },
                                    count = n, totalBeats = n.toDouble(), beatsPerBar = n,
                                    centerX = size.width * 0.5f, midiCenterY = size.height * 0.5f,
                                    staffSpace = size.height * 0.075f, staffWidth = size.width * 0.62f,
                                    cursor = pressed.size.coerceAtMost(n - 1),
                                )
                            }
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                        drawStrip(measurer, step, pressed.toList(), lastWrong, revealStage)
                    }
                }
                Visual.FINISH -> Unit
            }

            if (step.visual == Visual.FINISH) {
                Text("$pressCount presses", style = MaterialTheme.typography.displayMedium)
                Text(
                    "${(pressCount - shownCount).coerceAtLeast(0)} found on your own · " +
                        "$shownCount shown to you",
                    color = RunFg,
                )
            }

            if (lastWrong >= 0 && step.target() >= 0) {
                val d = KeyGeography.whiteKeyDelta(lastWrong, step.target())
                Text(
                    if (d == 0) "Nearly — that is the black key next to it." else
                        "${kotlin.math.abs(d)} white key${if (kotlin.math.abs(d) == 1) "" else "s"} " +
                            if (d < 0) "left." else "right.",
                    color = RunAmber,
                )
            }

            if (!everHeardAnything) {
                Text(
                    "No signal yet. The square plug goes into the piano, the small oval " +
                        "end into the tablet.",
                    color = RunAmber,
                    modifier = Modifier.widthIn(max = 700.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (satisfied) {
                    Button(onClick = { advance() }) { Text(step.nextLabel) }
                } else if (step.target() >= 0 || step.visual == Visual.HAND) {
                    OutlinedButton(onClick = { revealStage = (revealStage + 1).coerceAtMost(2) }) {
                        Text(if (revealStage == 0) "Show me" else "Show me the key")
                    }
                }
                if (step.visual == Visual.FINISH) {
                    Button(onClick = { advance() }) { Text(step.nextLabel) }
                }
            }
        }
    }
}

private enum class Visual { BOARD, HAND, CHART, FINISH }

private class RunStep(
    val id: String,
    val title: String,
    val says: String,
    val visual: Visual,
    val nextLabel: String = "Next",
    /** Marks drawn on the chart, if any. */
    val marks: IntArray = IntArray(0),
    /** Groups of two tinted green. */
    val showPairs: Boolean = false,
    /** Groups of three tinted green. */
    val showTriples: Boolean = false,
    /** Number the seven groups of two and highlight the middle one. */
    val countGroups: Boolean = false,
    /** Tint every C. */
    val showAllCs: Boolean = false,
    /** The single key this step wants, or -1 when it accepts a set. */
    val singleTarget: Int = -1,
    val accepts: (Int, List<Int>) -> Boolean,
    val complete: (List<Int>) -> Boolean,
) {
    fun target(): Int = singleTarget
}

private fun blockStartFor(index: Int): Int = when {
    index >= 13 -> 13
    index >= 9 -> 9
    index >= 5 -> 5
    else -> 0
}

private val FIRST_RUN: List<RunStep> = listOf(
    RunStep(
        id = "any", title = "Press anything", visual = Visual.BOARD,
        says = "Press any key on your piano. About twenty presses, six minutes. " +
            "Nothing here is scored.",
        accepts = { _, _ -> true }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "pair", title = "Twos and threes", visual = Visual.BOARD, showPairs = true,
        says = "The black keys sit in groups: two, three, two, three, all the way along. " +
            "Press both black keys of any green group.",
        accepts = { n, sofar ->
            KeyGeography.pairGroupIndex(n) >= 0 &&
                (sofar.isEmpty() || KeyGeography.pairGroupIndex(n) == KeyGeography.pairGroupIndex(sofar[0])) &&
                n !in sofar
        },
        complete = { it.size >= 2 },
    ),
    RunStep(
        id = "pair-else", title = "Somewhere else", visual = Visual.BOARD, showPairs = true,
        says = "Same shape, different place. Press both black keys of a green group " +
            "somewhere else.",
        accepts = { n, sofar ->
            KeyGeography.pairGroupIndex(n) >= 0 &&
                (sofar.isEmpty() || KeyGeography.pairGroupIndex(n) == KeyGeography.pairGroupIndex(sofar[0])) &&
                n !in sofar
        },
        complete = { it.size >= 2 },
    ),
    RunStep(
        id = "triple", title = "Groups of three", visual = Visual.BOARD, showTriples = true,
        says = "The other groups have three. Press all three black keys of any green group.",
        accepts = { n, sofar ->
            // Membership of a COMPLETE group, not a pitch-class test — the lone
            // low black key shares a pitch class with a group-of-three member.
            KeyGeography.inTripleGroup(n) && !KeyGeography.isOrphanBlackKey(n) &&
                (sofar.isEmpty() || KeyGeography.tripleGroupIndex(n) == KeyGeography.tripleGroupIndex(sofar[0])) &&
                n !in sofar
        },
        complete = { it.size >= 3 },
    ),
    RunStep(
        id = "middle-group", title = "The middle group", visual = Visual.BOARD, countGroups = true,
        says = "There are seven groups of two. The middle one has three groups on its " +
            "left and three on its right. Press either black key of the amber group.",
        accepts = { n, _ -> KeyGeography.pairGroupIndex(n) == KeyGeography.middleCPairGroupIndex() },
        complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "any-c", title = "C", visual = Visual.BOARD, showAllCs = true,
        says = "The white key just LEFT of a group of two is a C. There are eight of " +
            "them. Press any C.",
        accepts = { n, _ -> n % 12 == 0 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "middle-c", title = "Middle C", visual = Visual.BOARD,
        countGroups = true, singleTarget = 60,
        says = "The C at the middle group is called middle C. Press it.",
        accepts = { n, _ -> n == 60 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "hand", title = "Where your right hand goes", visual = Visual.HAND,
        says = "Right thumb on middle C. One finger per white key going right: 2, 3, 4, 5. " +
            "Press all five, any order.",
        accepts = { n, sofar -> n in KeyGeography.C_POSITION.toList() && n !in sofar },
        complete = { it.size >= 5 },
    ),
    RunStep(
        id = "unaided-c", title = "Middle C, nothing marked", visual = Visual.BOARD,
        singleTarget = 60,
        says = "Find middle C. Nothing is marked this time.",
        accepts = { n, _ -> n == 60 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "chart", title = "The chart", visual = Visual.CHART,
        marks = intArrayOf(60), singleTarget = 60,
        says = "This is the chart. A mark on it means one key — this mark is middle C. " +
            "Press it.",
        accepts = { n, _ -> n == 60 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "place-up", title = "Places", visual = Visual.CHART,
        marks = intArrayOf(62), singleTarget = 62,
        says = "A mark sits on a line or in a gap; each of those is one place. " +
            "One place up is one white key to the right. Press the new mark.",
        accepts = { n, _ -> n == 62 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "place-down", title = "The other direction", visual = Visual.CHART,
        marks = intArrayOf(59), singleTarget = 59,
        says = "This mark is one place DOWN from middle C. Nothing is marked on the keys. " +
            "Press it.",
        accepts = { n, _ -> n == 59 }, complete = { it.isNotEmpty() },
    ),
    RunStep(
        id = "three-marks", title = "Three marks", visual = Visual.CHART,
        marks = intArrayOf(60, 62, 64),
        says = "Three marks. Play them left to right — the cursor waits at each one. " +
            "Take as long as you like.",
        accepts = { n, sofar -> n == intArrayOf(60, 62, 64).getOrElse(sofar.size) { -1 } },
        complete = { it.size >= 3 },
    ),
    RunStep(
        id = "handoff", title = "What happens next", visual = Visual.CHART,
        marks = intArrayOf(60, 62, 64, 65), nextLabel = "Next",
        says = "Next you play four marks you have not seen. The chart does not move but " +
            "the cursor does not wait — press each key as it reaches. It counts for nothing.",
        accepts = { _, _ -> false }, complete = { true },
    ),
    RunStep(
        id = "finish", title = "Done", visual = Visual.FINISH,
        nextLabel = "Next · 4 marks, about 40 seconds",
        says = "",
        accepts = { _, _ -> false }, complete = { true },
    ),
)

// ------------------------------------------------------------------ drawing

private fun DrawScope.drawBoard(
    measurer: TextMeasurer,
    step: RunStep,
    pressed: List<Int>,
    wrong: Int,
    revealStage: Int,
) {
    val lo = KeyGeography.LOWEST
    val hi = KeyGeography.HIGHEST
    val whites = KeyGeography.whiteKeyCount()
    val w = size.width / whites
    val h = size.height * 0.72f
    val top = size.height - h
    val middleGroup = KeyGeography.middleCPairGroupIndex()

    var idx = 0
    for (n in lo..hi) {
        if (KeyGeography.isBlack(n)) continue
        val lit = when {
            n in pressed -> Blue
            n == wrong -> Blue
            step.showAllCs && n % 12 == 0 -> Green
            step.singleTarget == n && revealStage >= 2 -> Green
            step.countGroups && n == 60 && step.id == "middle-c" -> Green
            else -> White
        }
        drawRect(lit, Offset(idx * w + 0.5f, top), Size(w - 1f, h))
        idx++
    }
    idx = 0
    for (n in lo..hi) {
        if (!KeyGeography.isBlack(n)) { idx++; continue }
        val pg = KeyGeography.pairGroupIndex(n)
        val tg = KeyGeography.tripleGroupIndex(n)
        val orphan = KeyGeography.isOrphanBlackKey(n)
        val lit = when {
            n in pressed -> Blue
            n == wrong -> Blue
            orphan -> Black
            step.countGroups && pg == middleGroup -> Amber
            step.showPairs && pg >= 0 -> Green
            step.showTriples && tg >= 0 && !orphan -> Green
            revealStage >= 1 && step.singleTarget == 60 && pg == middleGroup -> Amber
            else -> Black
        }
        drawRect(lit, Offset(idx * w - w * 0.30f, top), Size(w * 0.60f, h * 0.62f))
    }

    if (step.countGroups) {
        var g = 0
        var i = 0
        for (n in lo..hi) {
            if (!KeyGeography.isBlack(n)) { i++; continue }
            if (KeyGeography.pairGroupIndex(n) == g && n % 12 == 1) {
                val laid = measurer.measure(
                    "${g + 1}",
                    TextStyle(fontSize = 11.sp, color = if (g == middleGroup) Amber else RunDim),
                )
                drawText(laid, topLeft = Offset(i * w - laid.size.width / 2f, 0f))
                g++
            }
        }
    }
}

private fun DrawScope.drawStrip(
    measurer: TextMeasurer,
    step: RunStep,
    pressed: List<Int>,
    wrong: Int,
    revealStage: Int,
) {
    val lo = 55
    val hi = 72
    var whites = 0
    for (n in lo..hi) if (!KeyGeography.isBlack(n)) whites++
    val w = size.width / whites
    val h = size.height
    var idx = 0
    for (n in lo..hi) {
        if (KeyGeography.isBlack(n)) continue
        val lit = when {
            n in pressed -> Blue
            n == wrong -> Blue
            step.singleTarget == n && revealStage >= 2 -> Green
            else -> White
        }
        drawRect(lit, Offset(idx * w + 1f, 0f), Size(w - 2f, h))
        idx++
    }
    idx = 0
    for (n in lo..hi) {
        if (KeyGeography.isBlack(n)) {
            drawRect(Black, Offset(idx * w - w * 0.29f, 0f), Size(w * 0.58f, h * 0.62f))
        } else {
            idx++
        }
    }
}

private fun DrawScope.drawHandOctave(measurer: TextMeasurer, pressed: List<Int>) {
    val lo = 60
    val hi = 72
    var whites = 0
    for (n in lo..hi) if (!KeyGeography.isBlack(n)) whites++
    val w = size.width / whites
    val h = size.height
    var idx = 0
    for (n in lo..hi) {
        if (KeyGeography.isBlack(n)) continue
        drawRect(if (n in pressed) Green else White, Offset(idx * w + 1f, 0f), Size(w - 2f, h))
        idx++
    }
    idx = 0
    for (n in lo..hi) {
        if (KeyGeography.isBlack(n)) {
            drawRect(Black, Offset(idx * w - w * 0.29f, 0f), Size(w * 0.58f, h * 0.62f))
        } else {
            idx++
        }
    }
    for (f in 0 until 5) {
        val cx = f * w + w / 2f
        val cy = h * 0.76f
        drawCircle(if (f == 0) Green else GreenDim, w * 0.24f, Offset(cx, cy))
        drawCircle(Color(0xFF0A140E), w * 0.24f, Offset(cx, cy), style = Stroke(2f))
        val laid = measurer.measure(
            "${f + 1}", TextStyle(fontSize = 15.sp, color = Color(0xFF06100A)),
        )
        drawText(laid, topLeft = Offset(cx - laid.size.width / 2f, cy - laid.size.height / 2f))
    }
}

private fun mutableStateListOfInts() = androidx.compose.runtime.mutableStateListOf<Int>()

const val PREF_FIRST_RUN_DONE = "first_run_done"
private const val PREF_FIRST_RUN_STEP = "first_run_step"

private val White = Color(0xFFF0F0EB)
private val Black = Color(0xFF181E24)
private val Green = Color(0xFF00E676)
private val GreenDim = Color(0xFF2A7F55)
private val Amber = Color(0xFFFFB74D)
private val Blue = Color(0xFF64C4FF)
private val RunFg = Color(0xFFC8D2D7)
private val RunDim = Color(0xFF78828C)
private val RunAmber = Color(0xFFFFB74D)
