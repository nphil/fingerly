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
import androidx.compose.material3.LinearProgressIndicator
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
import com.fingerly.core.play.HitJudge
import com.fingerly.core.session.FoundationsTrainer
import kotlinx.coroutines.launch

/**
 * Adaptive foundations trainer host. ADHD-informed structure: the mastery map
 * is always the home view (big picture, externalized progress), every drill is
 * ~45s and shaped identically, tips are ≤3 sentences, teaching happens in the
 * between-drill summary, and each sitting suggests a stop after a few drills.
 */
@Composable
fun FoundationsScreen(engine: MidiEngine, onCompleted: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository(FingerlyDatabase.get(context)) }
    val prefs = remember { context.getSharedPreferences("fingerly", 0) }

    var trainer by remember { mutableStateOf<FoundationsTrainer?>(null) }
    var drill by remember { mutableStateOf<FoundationsTrainer.Drill?>(null) }
    var playing by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var drillsThisSitting by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        trainer = FoundationsTrainer(repo.getSetting(SETTING_KEY))
    }

    fun persist() {
        val t = trainer ?: return
        scope.launch { repo.putSetting(SETTING_KEY, t.serialize()) }
    }

    fun finishCourse() {
        prefs.edit().putBoolean(PREF_FOUNDATIONS_DONE, true).apply()
        onCompleted()
    }

    fun onDrillEnded(view: NoteHighwayView, judge: HitJudge, d: FoundationsTrainer.Drill) {
        val t = trainer ?: return
        // Per-prompt results from wait-mode instrumentation.
        val wrongByIdx = HashMap<Int, MutableList<Int>>()
        for (k in 0 until view.wrongEventCount()) {
            wrongByIdx.getOrPut(view.wrongExpectedIdxAt(k)) { ArrayList() }
                .add(view.wrongPlayedNoteAt(k))
        }
        val results = d.prompts.mapIndexed { i, p ->
            FoundationsTrainer.PromptResult(
                atomId = p.atomId,
                correctFirstTry = wrongByIdx[i].isNullOrEmpty(),
                latencyMs = view.waitLatencyMsAt(i),
                expectedNote = p.midiNote,
                wrongPresses = wrongByIdx[i] ?: emptyList(),
            )
        }
        val firstTry = results.count { it.correctFirstTry }
        RemoteLog.log(
            "foundations",
            "${d.focusAtom} firstTry=$firstTry/${results.size} " +
                "avgLat=${results.map { it.latencyMs }.average().toInt()}ms test=${d.isTest}",
        )
        playing = false
        drillsThisSitting++
        if (d.isTest) {
            if (t.testPassed(results)) {
                t.recordResults(results)
                persist()
                summary = "Checkpoint passed. " + t.report()
                finishCourse()
                return
            }
            t.recordResults(results)
            persist()
            summary = "Checkpoint not passed: $firstTry/${results.size} first-try. " + t.report()
        } else {
            t.recordResults(results)
            persist()
            summary = "$firstTry/${results.size} first-try · " +
                "avg ${results.map { it.latencyMs }.average().toInt() / 1000f}s per key"
        }
        drill = null
    }

    val t = trainer
    Box(Modifier.fillMaxSize()) {
        when {
            t == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Dim2)

            playing && drill != null -> {
                val d = drill!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        NoteHighwayView(ctx, engine.ring, FoundationsTrainer.toScore(d)).apply {
                            tapToRestart = false
                            leadInMs = 1500
                            waitMode = true
                            onEnded = { judge -> onDrillEnded(this, judge, d) }
                        }
                    },
                )
                Text(
                    d.title + if (d.isTest) " — checkpoint" else "",
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    color = Dim2,
                )
            }

            else -> {
                // Home view: the map first, always (big picture + visible progress).
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Basics training", style = MaterialTheme.typography.headlineMedium)
                    t.masteryRows().forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (row.mastered) "✓ " else "   ") + row.label,
                                Modifier.width(260.dp),
                                color = if (row.mastered) Mint2 else Fg2,
                            )
                            LinearProgressIndicator(
                                progress = { row.percent / 100f },
                                modifier = Modifier.width(220.dp),
                                color = if (row.mastered) Mint2 else Color(0xFFFFB74D),
                            )
                            Text("  ${row.percent}%", color = Dim2)
                        }
                    }
                    if (summary != null) {
                        Text(summary!!, color = Fg2, modifier = Modifier.widthIn(max = 760.dp))
                    }
                    val next = remember(summary, drillsThisSitting) { t.nextDrill() }
                    if (next.tip != null) {
                        Text(next.tip!!, color = Color(0xFFFFB74D), modifier = Modifier.widthIn(max = 760.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            drill = next
                            summary = null
                            playing = true
                        }) {
                            Text(
                                if (next.isTest) "Checkpoint test" else "Drill: ${next.title} (~45s)",
                            )
                        }
                        if (drillsThisSitting >= 5) {
                            OutlinedButton(onClick = onExit) { Text("Good stopping point — done") }
                        }
                        OutlinedButton(onClick = { finishCourse() }) { Text("Skip basics") }
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
