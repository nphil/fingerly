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
            val revealed = view.wasRevealedAt(i)
            val latency = view.waitLatencyMsAt(i)
            FoundationsTrainer.PromptResult(
                atomId = p.atomId,
                // Unaided = first press correct AND no reveal was needed.
                unaided = wrongByIdx[i].isNullOrEmpty() && !revealed && latency >= 0,
                revealed = revealed,
                latencyMs = latency,
                expectedNote = p.midiNote,
                wrongPresses = wrongByIdx[i] ?: emptyList(),
            )
        }
        t.recordResults(results, dayIndex)
        persist(t)

        val unaided = results.count { it.unaided }
        val revealedCount = results.count { it.revealed }
        val latencies = results.filter { it.unaided && it.latencyMs >= 0 }.map { it.latencyMs }
        // Raw per-trial rows: the durable asset every future retune runs on.
        scope.launch {
            repo.saveFoundationsTrials(dayIndex, d.focusAtom, results)
        }
        RemoteLog.log(
            "foundations",
            "${d.focusAtom} unaided=$unaided/${results.size} revealed=$revealedCount " +
                "medianLat=${latencies.sorted().getOrNull(latencies.size / 2) ?: -1}ms " +
                "rung=${t.atoms.getValue(d.focusAtom).rung} " +
                "days=${t.atoms.getValue(d.focusAtom).daysCredited}",
        )
        summary = buildString {
            append("$unaided/${results.size} from memory")
            if (revealedCount > 0) append(" · $revealedCount needed the landmark")
            if (latencies.isNotEmpty()) {
                append(" · ${latencies.sorted()[latencies.size / 2] / 1000f}s median")
            }
        }
        runningDrill = null
        version++
        if (t.songGateOpen()) prefs.edit().putBoolean(PREF_FOUNDATIONS_DONE, true).apply()
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
                    if (next?.tip != null) {
                        Text(
                            next.tip!!,
                            color = Color(0xFFFFB74D),
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (next != null) {
                            Button(onClick = {
                                t.startDrill(next)
                                persist(t)
                                summary = null
                                runningDrill = next
                            }) { Text("${next.prompts.size} keys · ~60s") }
                        } else {
                            Text(
                                t.sittingFinishLabel() + ". Nothing left today.",
                                color = Mint2,
                                modifier = Modifier.widthIn(max = 700.dp),
                            )
                        }
                        OutlinedButton(onClick = onCompleted) {
                            Text(if (t.songGateOpen()) "Go to song" else "Songs anyway")
                        }
                    }
                }
            }
        }
        BackText(onExit)
    }
}

const val PREF_FOUNDATIONS_DONE = "foundations_done"
private const val SETTING_KEY = "foundations_trainer_state"
private val Mint2 = Color(0xFF00E676)
private val Fg2 = Color(0xFFC8D2D7)
private val Dim2 = Color(0xFF78828C)
