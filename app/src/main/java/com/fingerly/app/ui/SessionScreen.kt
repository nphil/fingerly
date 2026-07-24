package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.fingerly.core.session.AttemptResult
import com.fingerly.core.session.Decomposer
import com.fingerly.core.session.Diagnosis
import com.fingerly.core.session.HAND_BOTH
import com.fingerly.core.session.Passage
import com.fingerly.core.session.PracticeScoreFactory
import com.fingerly.core.session.SessionEngine
import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import kotlinx.coroutines.launch

private sealed interface SessionUi {
    data object Loading : SessionUi
    class Briefing(val step: SessionEngine.Step, val diagnostic: String?) : SessionUi
    class Playing(val step: SessionEngine.Step, val practice: Score, val listen: Boolean) : SessionUi
    class Finished(val label: String) : SessionUi
}

/**
 * The practice session (SPEC §3): auto-started, structured, zero decision menus.
 * Every screen states exactly what to do (SPEC §2.5); feedback is diagnostic
 * only (SPEC §2.8); "I'm lost" always decomposes (SPEC §2.2).
 */
@Composable
fun SessionScreen(engine: MidiEngine, score: Score, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository(FingerlyDatabase.get(context)) }
    val passages = remember { Decomposer.decompose(score) }

    var ui by remember { mutableStateOf<SessionUi>(SessionUi.Loading) }
    var sessionEngine by remember { mutableStateOf<SessionEngine?>(null) }
    var idMap by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    var sessionId by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val map = repo.ensureSong(
            score, "bundled:ode_to_joy_beginner", "Ludwig van Beethoven", 0, passages,
        )
        val progress = repo.loadProgress(map)
        val se = SessionEngine(passages, progress, nowMs = { System.currentTimeMillis() })
        idMap = map
        sessionId = repo.startSession()
        sessionEngine = se
        ui = SessionUi.Briefing(se.begin(), null)
        RemoteLog.log("session", "started, ${passages.size} passages, known=${progress.size}")
    }

    DisposableEffect(Unit) {
        onDispose { engine.setDemoSoundEnabled(false) }
    }

    fun toBriefing(step: SessionEngine.Step, diagnostic: String?) {
        engine.setDemoSoundEnabled(false)
        ui = SessionUi.Briefing(step, diagnostic)
    }

    fun startRun(step: SessionEngine.Step, listen: Boolean) {
        val practice = PracticeScoreFactory.build(score, step.passage, step.setting)
        engine.setDemoSoundEnabled(listen)
        ui = SessionUi.Playing(step, practice, listen)
    }

    fun onRunEnded(step: SessionEngine.Step, judge: HitJudge, listen: Boolean) {
        val se = sessionEngine ?: return
        if (listen) {
            toBriefing(step, null)
            return
        }
        val result = AttemptResult(
            accuracyPercent = judge.accuracyPercent(),
            hits = judge.hits,
            misses = judge.misses,
            extras = judge.extras,
            avgAbsErrMs = judge.avgAbsErrorMs(),
            meanSignedErrMs = judge.meanSignedErrorMs(),
            leftAccuracy = judge.handAccuracyPercent(ChartNote.HAND_LEFT),
            rightAccuracy = judge.handAccuracyPercent(ChartNote.HAND_RIGHT),
        )
        val next = se.onAttempt(result)
        val liveProgress = se.progressFor(step.passage.id)
        scope.launch {
            val dbId = idMap[step.passage.id]
            if (dbId != null && liveProgress != null) {
                repo.recordAttempt(
                    sessionId, dbId, step.setting, score.tempoBpm, result, liveProgress,
                )
            }
            RemoteLog.log(
                "session",
                "${step.phase} p${step.passage.id} rung=${step.ladderIndex} " +
                    "acc=${result.accuracyPercent.toInt()}% -> ${next.phase} rung=${next.ladderIndex}",
            )
        }
        if (next.phase == SessionEngine.Phase.DONE) {
            val label = se.finishLabel()
            scope.launch { repo.endSession(sessionId, label) }
            ui = SessionUi.Finished(label)
        } else {
            toBriefing(next, Diagnosis.of(result))
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val state = ui) {
            SessionUi.Loading -> Text(
                "Loading session…",
                Modifier.align(Alignment.Center),
                color = Color(0xFF78828C),
            )

            is SessionUi.Briefing -> BriefingContent(
                step = state.step,
                diagnostic = state.diagnostic,
                onStart = { startRun(state.step, listen = false) },
                onListen = { startRun(state.step, listen = true) },
                onLost = { sessionEngine?.let { toBriefing(it.imLost(), null) } },
            )

            is SessionUi.Playing -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // Slower rungs get a proportionally wider hit window so
                        // beginners aren't punished by performance-grade timing.
                        val window = (150.0 / state.step.setting.tempoMultiplier)
                            .toLong().coerceAtMost(300L)
                        NoteHighwayView(
                            ctx, engine.ring, state.practice, engine::injectVirtual,
                            hitWindowMs = window, missAfterMs = window * 2,
                        ).apply {
                            tapToRestart = false
                            leadInMs = 2000
                            waitMode = state.step.setting.wait
                            autoplay = state.listen
                            onEnded = { judge -> onRunEnded(state.step, judge, state.listen) }
                        }
                    },
                )
                Text(
                    stepLabel(state.step) + if (state.listen) "  (listening)" else "",
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    color = Color(0xFF78828C),
                )
                if (!state.listen) {
                    OutlinedButton(
                        onClick = { sessionEngine?.let { toBriefing(it.imLost(), null) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                    ) { Text("I'm lost") }
                }
            }

            is SessionUi.Finished -> FinishedContent(
                label = state.label,
                onDone = onExit,
                onExtend = {
                    sessionEngine?.let { toBriefing(it.extend(), null) }
                },
            )
        }
        BackText(onExit)
    }
}

@Composable
private fun BriefingContent(
    step: SessionEngine.Step,
    diagnostic: String?,
    onStart: () -> Unit,
    onListen: () -> Unit,
    onLost: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(phaseName(step.phase), style = MaterialTheme.typography.headlineMedium)
            Text(
                stepLabel(step),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF00E676),
            )
            if (diagnostic != null) {
                Text(diagnostic, color = Color(0xFF78828C))
            }
            Text(
                "Press the key when it lights up; tap or hold, only the press is graded. " +
                    "To find any C on your piano: the white key just left of each PAIR of black keys.",
                color = Color(0xFF78828C),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStart) { Text("Play") }
                OutlinedButton(onClick = onListen) { Text("Listen first") }
                OutlinedButton(onClick = onLost) { Text("I'm lost") }
            }
        }
    }
}

@Composable
private fun FinishedContent(label: String, onDone: () -> Unit, onExtend: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Session complete", style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.titleLarge, color = Color(0xFF00E676))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDone) { Text("Done") }
                OutlinedButton(onClick = onExtend) { Text("Keep going") }
            }
        }
    }
}

private fun phaseName(phase: SessionEngine.Phase): String = when (phase) {
    SessionEngine.Phase.WARMUP -> "Warm-up"
    SessionEngine.Phase.WORK -> "Work"
    SessionEngine.Phase.REVIEW -> "Review"
    SessionEngine.Phase.VICTORY -> "Victory lap"
    SessionEngine.Phase.DONE -> "Done"
}

/** Exact, verifiable instruction (SPEC §2.5): bars, hand, tempo, first note. */
private fun stepLabel(step: SessionEngine.Step): String {
    val s = step.setting
    val hand = when (s.hand) {
        ChartNote.HAND_RIGHT -> "right hand"
        ChartNote.HAND_LEFT -> "left hand"
        HAND_BOTH -> "both hands"
        else -> "both hands"
    }
    val bars = "Bars ${step.passage.startMeasure}–${step.passage.startMeasure + s.bars - 1}"
    val first = step.passage.notes
        .filter { s.hand == HAND_BOTH || it.hand == s.hand }
        .minByOrNull { it.startSeconds }
    val firstTxt = first?.let { "  ·  first note ${noteName(it.midiNote)}" } ?: ""
    val pace = if (s.wait) {
        "notes wait for you"
    } else {
        "${(s.tempoMultiplier * 100).toInt()}% tempo"
    }
    return "$bars  ·  $hand  ·  $pace$firstTxt"
}

private val NOTE_NAMES =
    arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun noteName(midi: Int): String = "${NOTE_NAMES[midi % 12]}${midi / 12 - 1}"
