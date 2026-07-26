package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fingerly.app.data.FingerlyDatabase
import com.fingerly.app.data.SessionRepository
import com.fingerly.app.highway.NoteHighwayView
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.session.FoundationsTrainer
import kotlinx.coroutines.launch

/**
 * Foundations trainer host: RECALL drills on keyboard geography.
 *
 * The map is the home view (big picture, monotone counters that never drop).
 * Each drill is ~8 prompts with the effort quantum stated on the button. The
 * drill screen shows no verdict and no streak — only a depleting prompt bar —
 * with the blunt summary after the drill (see docs/LEARNING.md for citations).
 */
@Composable
fun FoundationsScreen(engine: MidiEngine, onCompleted: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository(FingerlyDatabase.get(context)) }
    val prefs = remember { context.getSharedPreferences("fingerly", 0) }

    var trainer by remember { mutableStateOf<FoundationsTrainer?>(null) }
    var runningDrill by remember { mutableStateOf<FoundationsTrainer.Drill?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }

    val dayIndex = remember { (System.currentTimeMillis() / 86_400_000L).toInt() }
    // There is no other way to answer a prompt: the drill takes MIDI only. With
    // no piano attached every prompt force-advances, so the run used to grind
    // through ~100s of untouchable screen and then report "0/8 from memory" —
    // a diagnosis of the learner for a fact about the cable.
    val midi by engine.connectionState.collectAsState()

    LaunchedEffect(Unit) {
        trainer = FoundationsTrainer(repo.getSetting(SETTING_KEY)).apply { startSitting(dayIndex) }
    }

    fun persist(t: FoundationsTrainer) {
        scope.launch { repo.putSetting(SETTING_KEY, t.serialize()) }
    }

    fun onDrillEnded(view: NoteHighwayView, d: FoundationsTrainer.Drill) {
        val t = trainer ?: return
        val wrongByIdx = HashMap<Int, MutableList<Int>>()
        for (k in 0 until view.wrongEventCount()) {
            wrongByIdx.getOrPut(view.wrongExpectedIdxAt(k)) { ArrayList() }
                .add(view.wrongPlayedNoteAt(k))
        }
        val results = d.prompts.mapIndexed { i, p ->
            val demonstrated = view.wasDemonstratedAt(i)
            // A demonstration also sets the reveal flag (both withhold credit),
            // but only a reveal the LEARNER ran out of time on is a retrieval
            // failure. Conflating them made a flawless first drill — where every
            // atom is met for the first time — report as half wrong.
            val revealed = view.wasRevealedAt(i) && !demonstrated
            val latency = view.waitLatencyMsAt(i)
            FoundationsTrainer.PromptResult(
                atomId = p.atomId,
                // Unaided = first press correct AND nothing was shown.
                unaided = wrongByIdx[i].isNullOrEmpty() && !revealed &&
                    !demonstrated && latency >= 0,
                revealed = revealed,
                latencyMs = latency,
                expectedNote = p.midiNote,
                wrongPresses = wrongByIdx[i] ?: emptyList(),
                demonstrated = demonstrated,
            )
        }
        t.recordResults(results, dayIndex)
        persist(t)

        val scored = results.filter { !it.demonstrated }
        val unaided = scored.count { it.unaided }
        val demoCount = results.count { it.demonstrated }
        val revealedCount = results.count { it.revealed }
        val latencies = results.filter { it.unaided && it.latencyMs >= 0 }.map { it.latencyMs }
        // Raw per-trial rows: the durable asset every future retune runs on.
        scope.launch {
            repo.saveFoundationsTrials(dayIndex, d.focusAtom, results)
        }
        RemoteLog.log(
            "foundations",
            "${d.focusAtom} unaided=$unaided/${scored.size} demo=$demoCount " +
                "revealed=$revealedCount " +
                "medianLat=${latencies.sorted().getOrNull(latencies.size / 2) ?: -1}ms " +
                "rung=${t.atoms.getValue(d.focusAtom).rung} " +
                "days=${t.atoms.getValue(d.focusAtom).daysCredited}",
        )
        summary = buildString {
            append("$unaided/${scored.size} from memory")
            if (demoCount > 0) append(" · $demoCount shown for the first time")
            if (revealedCount > 0) append(" · $revealedCount needed the landmark")
            if (latencies.isNotEmpty()) {
                append(" · ${latencies.sorted()[latencies.size / 2] / 1000f}s median")
            }
        }
        runningDrill = null
        version++
    }

    val t = trainer
    Box(Modifier.fillMaxSize()) {
        when {
            t == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Dim2)

            runningDrill != null -> {
                val d = runningDrill!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        NoteHighwayView(
                            ctx, engine.ring, FoundationsTrainer.toScore(d),
                            matchAnyOctave = BooleanArray(d.prompts.size) {
                                d.prompts[it].matchAnyOctave
                            },
                        ).apply {
                            tapToRestart = false
                            leadInMs = 900
                            waitMode = true
                            recallMode = true // draw no answer: this is a recall test
                            showHud = false // no live verdict, no streak
                            revealAfterMs = t.config.revealAfterMs
                            forceAdvanceAfterMs = t.config.forceAdvanceAfterMs
                            promptLabels = Array(d.prompts.size) { d.prompts[it].label }
                            // Staff prompts (SPEC §4a-F): notation instead of words.
                            promptRender = ByteArray(d.prompts.size) {
                                d.prompts[it].render.toByte()
                            }
                            promptClef = ByteArray(d.prompts.size) {
                                d.prompts[it].clef.toByte()
                            }
                            promptDemo = BooleanArray(d.prompts.size) {
                                d.prompts[it].demonstrate
                            }
                            onEnded = { onDrillEnded(this, d) }
                        }
                    },
                )
            }

            else -> {
                val rows = remember(version) { t.masteryRows() }
                val next = remember(version) { t.previewDrill() }
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Basics", style = MaterialTheme.typography.headlineMedium)
                    rows.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (row.mastered) "✓ " else if (row.atCriterion) "· " else "  ") + row.label,
                                Modifier.width(220.dp),
                                color = if (row.mastered) Mint2 else Fg2,
                            )
                            Text(
                                "${row.hitsToday}/${row.hitsWanted} today   " +
                                    "${row.daysCredited}/${row.daysWanted} days" +
                                    (if (row.rung > 0) "   +${row.rung} oct" else ""),
                                color = Dim2,
                            )
                        }
                    }
                    if (summary != null) {
                        Text(summary!!, color = Fg2, modifier = Modifier.widthIn(max = 760.dp))
                    }
                    // The sitting's named finish state, always on screen. Stopping
                    // early is the normal case, and a finish state only reachable
                    // by exhaustion is one the learner never sees (SPEC §3.5).
                    Text(
                        remember(version) { t.sittingFinishLabel() },
                        color = Mint2,
                        modifier = Modifier.widthIn(max = 760.dp),
                    )
                    if (next?.tip != null) {
                        Text(
                            next.tip!!,
                            color = Amber2,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!midi.connected) {
                            Text(
                                "Piano not connected. Plug it in over USB-C — " +
                                    "it is picked up automatically.",
                                color = Amber2,
                                modifier = Modifier.widthIn(max = 700.dp),
                            )
                        } else if (next != null) {
                            Button(onClick = {
                                t.startDrill(next)
                                persist(t)
                                summary = null
                                runningDrill = next
                            }) { Text("${next.prompts.size} keys · ~60s") }
                        } else {
                            Text(
                                "Nothing left today.",
                                color = Mint2,
                                modifier = Modifier.widthIn(max = 700.dp),
                            )
                        }
                        // Never gated. Basics are what the app serves by default;
                        // the song path is always one tap away, and choosing it
                        // sticks (SPEC §4: note names are never a gate).
                        OutlinedButton(onClick = onCompleted) { Text("Go to song") }
                    }
                }
            }
        }
        BackText(onExit)
    }
}

/** Which path the learner was last on. Resumption, not a gate. */
const val PREF_LAST_PATH = "last_path"
const val PATH_BASICS = "basics"
const val PATH_SONG = "song"
private const val SETTING_KEY = "foundations_trainer_state"
private val Mint2 = Color(0xFF00E676)
private val Amber2 = Color(0xFFFFB74D)
private val Fg2 = Color(0xFFC8D2D7)
private val Dim2 = Color(0xFF78828C)
