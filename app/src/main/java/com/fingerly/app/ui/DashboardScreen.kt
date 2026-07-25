package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fingerly.app.data.FingerlyDatabase
import com.fingerly.app.data.SessionRepository
import com.fingerly.core.session.LearnerProfile
import com.fingerly.core.song.ChartNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Progress dashboard (SPEC §3): hard numbers only — accuracy, skills, timing,
 * session history. No vibes, no praise (SPEC §2.8).
 */
@Composable
fun DashboardScreen(engine: com.fingerly.app.midi.MidiEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SessionRepository(FingerlyDatabase.get(context)) }
    var report by remember { mutableStateOf<LearnerProfile.Report?>(null) }
    var sessions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dueCount by remember { mutableStateOf(0) }
    var pairs by remember { mutableStateOf<List<SessionRepository.RecordingPair>>(emptyList()) }
    var playing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        report = LearnerProfile.analyze(repo.loadAttemptRecords())
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        sessions = repo.recentSessions().map { s ->
            fmt.format(Date(s.startedAtEpochMs)) to (s.finishStateLabel ?: "(unfinished)")
        }
        dueCount = repo.dueReviewCount()
        pairs = repo.beforeAfterPairs()
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { engine.stopPlayback() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp, vertical = 64.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Progress", style = MaterialTheme.typography.headlineMedium)

            val r = report
            if (r == null || r.totalAttempts == 0) {
                Text("No attempts recorded yet.", color = Dim)
            } else {
                Text("${r.totalAttempts} attempts recorded  ·  $dueCount reviews due now", color = Dim)

                Text("Hands", style = MaterialTheme.typography.titleMedium, color = Mint)
                Text(
                    "left ${pct(r.leftEma)}  ·  right ${pct(r.rightEma)}  ·  " +
                        "weaker: ${if (r.weakerHand() == ChartNote.HAND_LEFT) "left" else "right"}",
                    color = Fg,
                )

                Text("Timing", style = MaterialTheme.typography.titleMedium, color = Mint)
                Text(
                    when {
                        r.timingBiasMs > 20 -> "you play late by ~${r.timingBiasMs}ms on average"
                        r.timingBiasMs < -20 -> "you rush by ~${-r.timingBiasMs}ms on average"
                        else -> "no consistent early/late bias (${r.timingBiasMs}ms)"
                    },
                    color = Fg,
                )

                Text("Skills", style = MaterialTheme.typography.titleMedium, color = Mint)
                if (r.skillStats.isEmpty()) {
                    Text("no skill data yet", color = Dim)
                } else {
                    r.skillStats.entries.sortedBy { it.value.ema }.forEach { (skill, stat) ->
                        Text("$skill  ${pct(stat.ema)}  (${stat.attempts} reps)", color = Fg)
                    }
                    val weak = r.weakestSkills()
                    if (weak.isNotEmpty()) {
                        Text("current focus: ${weak.joinToString(", ")}", color = Color(0xFFFFB74D))
                    }
                }

                if (pairs.isNotEmpty()) {
                    Text("Then vs now", style = MaterialTheme.typography.titleMedium, color = Mint)
                    pairs.forEach { pair ->
                        Text(pair.label, color = Fg)
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                playing = "${pair.label} (first)"
                                engine.playRecording(repo.readRecording(pair.first)) { playing = null }
                            }) { Text("Play first") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                playing = "${pair.label} (latest)"
                                engine.playRecording(repo.readRecording(pair.latest)) { playing = null }
                            }) { Text("Play latest") }
                        }
                    }
                    if (playing != null) Text("playing: $playing", color = Dim)
                }

                Text("Sessions", style = MaterialTheme.typography.titleMedium, color = Mint)
                sessions.forEach { (time, label) -> Text("$time — $label", color = Fg) }
            }
        }
        BackText(onBack)
    }
}

private val Mint = Color(0xFF00E676)
private val Fg = Color(0xFFC8D2D7)
private val Dim = Color(0xFF78828C)

private fun pct(v: Float): String = if (v < 0) "—" else "${v.toInt()}%"
