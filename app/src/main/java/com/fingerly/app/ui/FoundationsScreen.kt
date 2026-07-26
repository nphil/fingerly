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
import com.fingerly.app.data.FoundationsProbeEntity
import com.fingerly.app.data.SessionRepository
import com.fingerly.app.highway.NoteHighwayView
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.notation.ExcerptBank
import com.fingerly.core.notation.Staff
import com.fingerly.core.song.ChartNote
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
    // The cold read (SPEC §4a-F item F3): the sitting's FIRST act, every sitting,
    // from the very first one — before any of it is learnable. It is the only
    // measurement here that is not a proxy, and it gates nothing.
    var probe by remember { mutableStateOf<ExcerptBank.Excerpt?>(null) }
    var probeDue by remember { mutableStateOf(false) }
    var probeSummary by remember { mutableStateOf<String?>(null) }
    var bankExhausted by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showBrief by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(RemoteLog.isEnabled()) }
    var lastProbe by remember { mutableStateOf<String?>(null) }
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
        repo.allProbes().lastOrNull()?.let {
            lastProbe = "${it.hits}/${it.noteCount} pitches, ${it.avgAbsErrorMs}ms off"
        }
        if (repo.probesToday(dayIndex) == 0) {
            val next = ExcerptBank.nextUnseen(repo.consumedExcerpts())
            if (next == null) bankExhausted = true else probeDue = true
        }
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
        // A hands-together prompt emits TWO chart notes, so prompt index and
        // chart index stopped being interchangeable — and every per-prompt
        // measurement is keyed by chart index.
        val starts = FoundationsTrainer.promptChartStarts(d)
        val results = d.prompts.mapIndexed { i, p ->
            val at = starts[i]
            val span = FoundationsTrainer.chartNotesPerPrompt(p)
            val demonstrated = (at until at + span).any { view.wasDemonstratedAt(it) }
            // A demonstration also sets the reveal flag (both withhold credit),
            // but only a reveal the LEARNER ran out of time on is a retrieval
            // failure. Conflating them made a flawless first drill — where every
            // atom is met for the first time — report as half wrong.
            val revealed = (at until at + span).any { view.wasRevealedAt(it) } && !demonstrated
            // Slowest of the pair: a hands-together prompt is not done until
            // BOTH notes have landed.
            val latency = (at until at + span).map { view.waitLatencyMsAt(it) }
                .let { if (it.any { l -> l < 0 }) -1 else it.max() }
            val wrong = (at until at + span).flatMap { wrongByIdx[it] ?: emptyList() }
            FoundationsTrainer.PromptResult(
                atomId = p.atomId,
                // Unaided = every note of this prompt landed first time, with
                // nothing shown.
                unaided = wrong.isEmpty() && !revealed && !demonstrated && latency >= 0,
                revealed = revealed,
                latencyMs = latency,
                expectedNote = p.midiNote,
                wrongPresses = wrong,
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

    fun onProbeEnded(judge: com.fingerly.core.play.HitJudge, e: ExcerptBank.Excerpt) {
        val accuracy = judge.accuracyPercent()
        scope.launch {
            repo.saveProbe(
                FoundationsProbeEntity(
                    excerptId = e.id,
                    tier = e.tier,
                    atEpochMs = System.currentTimeMillis(),
                    dayIndex = dayIndex,
                    firstAttemptOfSitting = true,
                    noteCount = judge.noteCount,
                    hits = judge.hits,
                    misses = judge.misses,
                    extras = judge.extras,
                    pitchAccuracy = accuracy,
                    avgAbsErrorMs = judge.avgAbsErrorMs().toInt(),
                    meanSignedErrMs = judge.meanSignedErrorMs().toInt(),
                    timingCoverage = judge.timingCoverage(),
                    leftAccuracy = judge.handAccuracyPercent(ChartNote.HAND_LEFT),
                    rightAccuracy = judge.handAccuracyPercent(ChartNote.HAND_RIGHT),
                    handsTogetherOnsets = e.scorableHandsTogetherOnsets(),
                    // No scaffold is shown during a cold read; that is the point.
                    scaffoldState = 0,
                ),
            )
        }
        RemoteLog.log(
            "coldread",
            "${e.id} tier=${e.tier} acc=${accuracy.toInt()}% " +
                "hit=${judge.hits}/${judge.noteCount} " +
                "err=${judge.avgAbsErrorMs()}ms cov=${(judge.timingCoverage() * 100).toInt()}%",
        )
        probeSummary = "Cold read: ${judge.hits}/${judge.noteCount} pitches, " +
            "${judge.avgAbsErrorMs()}ms average timing. Counts for nothing — it is the measurement."
        probe = null
        probeDue = false
    }

    val t = trainer
    Box(Modifier.fillMaxSize()) {
        when {
            t == null -> Text("Loading…", Modifier.align(Alignment.Center), color = Dim2)

            probe != null -> {
                val e = probe!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val sorted = e.notes.sortedWith(compareBy({ it.startBeat }, { it.midi }))
                        NoteHighwayView(
                            ctx, engine.ring, e.toScore(),
                            // Wide on purpose: pitch accuracy and timing are two
                            // separate conditions, and the default ±150ms window
                            // would score a slow-but-correct read as zero pitches.
                            hitWindowMs = ExcerptBank.COLD_READ_HIT_WINDOW_MS,
                            missAfterMs = ExcerptBank.COLD_READ_MISS_AFTER_MS,
                        ).apply {
                            tapToRestart = false
                            leadInMs = ExcerptBank.COLD_READ_LEAD_IN_MS
                            waitMode = false // condition 3: the read is played IN TIME
                            showHud = false
                            excerptMode = true
                            excerptMidi = IntArray(sorted.size) { sorted[it].midi }
                            excerptStartBeats = DoubleArray(sorted.size) { sorted[it].startBeat }
                            excerptDurationBeats =
                                DoubleArray(sorted.size) { sorted[it].durationBeats }
                            excerptClefs = ByteArray(sorted.size) {
                                if (sorted[it].hand == ChartNote.HAND_LEFT) {
                                    Staff.CLEF_BASS.toByte()
                                } else {
                                    Staff.CLEF_TREBLE.toByte()
                                }
                            }
                            excerptTotalBeats = e.totalBeats
                            excerptBeatsPerBar = e.beatsPerBar
                            onEnded = { judge -> onProbeEnded(judge, e) }
                        }
                    },
                )
            }

            runningDrill != null -> {
                val d = runningDrill!!
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val chartScore = FoundationsTrainer.toScore(d)
                        val chartStarts = FoundationsTrainer.promptChartStarts(d)
                        // Chart-note index → the prompt that produced it.
                        val ownerOf = IntArray(chartScore.notes.size)
                        d.prompts.forEachIndexed { pi, p ->
                            val from = chartStarts[pi]
                            repeat(FoundationsTrainer.chartNotesPerPrompt(p)) {
                                if (from + it < ownerOf.size) ownerOf[from + it] = pi
                            }
                        }
                        NoteHighwayView(
                            ctx, engine.ring, chartScore,
                            matchAnyOctave = BooleanArray(chartScore.notes.size) {
                                d.prompts[ownerOf[it]].matchAnyOctave
                            },
                        ).apply {
                            tapToRestart = false
                            leadInMs = 900
                            waitMode = true
                            recallMode = true // draw no answer: this is a recall test
                            showHud = false // no live verdict, no streak
                            revealAfterMs = t.config.revealAfterMs
                            forceAdvanceAfterMs = t.config.forceAdvanceAfterMs
                            promptLabels = Array(chartScore.notes.size) { d.prompts[ownerOf[it]].label }
                            // Staff prompts (SPEC §4a-F): notation instead of words.
                            promptRender = ByteArray(chartScore.notes.size) {
                                d.prompts[ownerOf[it]].render.toByte()
                            }
                            promptClef = ByteArray(chartScore.notes.size) {
                                d.prompts[ownerOf[it]].clef.toByte()
                            }
                            promptDemo = BooleanArray(chartScore.notes.size) {
                                d.prompts[ownerOf[it]].demonstrate
                            }
                            promptScaffold = FloatArray(chartScore.notes.size) {
                                d.prompts[ownerOf[it]].scaffoldAlpha
                            }
                            onEnded = { onDrillEnded(this, d) }
                        }
                    },
                )
            }

            else -> {
                val rows = remember(version) { t.masteryRows() }
                val next = remember(version) { t.previewDrill() }
                val solid = rows.count { it.mastered }

                // ONE action, chosen by the app. SPEC §2 forbids a decision menu
                // before playing, and thirteen counters with no legend is a menu
                // wearing a progress bar. What is on screen is: what you are
                // about to do, how long it takes, and one button.
                val action: FoundationsAction = when {
                    !midi.connected -> FoundationsAction(
                        title = "Plug in your piano",
                        detail = "USB-C. It is picked up automatically.",
                        button = null,
                    )
                    probeDue -> FoundationsAction(
                        title = "Play these four bars",
                        detail = "You have not seen them before. Get it wrong — " +
                            "this is the measurement, not a test you can fail.",
                        button = "Start · about 40s",
                        onClick = {
                            scope.launch {
                                probe = ExcerptBank.nextUnseen(repo.consumedExcerpts())
                                if (probe == null) {
                                    bankExhausted = true
                                    probeDue = false
                                }
                            }
                        },
                    )
                    next != null -> FoundationsAction(
                        title = next.title,
                        detail = next.tip
                            ?: "A note appears on the staff. Find it on the piano.",
                        button = "Start · ${next.prompts.size} notes, about a minute",
                        onClick = {
                            t.startDrill(next)
                            persist(t)
                            summary = null
                            runningDrill = next
                        },
                    )
                    else -> FoundationsAction(
                        title = "Done for today",
                        detail = "Come back tomorrow — the spacing is what makes it " +
                            "stick, so more today would not help.",
                        button = null,
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(action.title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        action.detail,
                        color = Fg2,
                        modifier = Modifier.widthIn(max = 620.dp),
                    )
                    if (action.button != null) {
                        Button(onClick = action.onClick) { Text(action.button) }
                    }

                    // What just happened, if anything did.
                    if (probeSummary != null) {
                        Text(probeSummary!!, color = Amber2, modifier = Modifier.widthIn(max = 700.dp))
                    }
                    if (summary != null) {
                        Text(summary!!, color = Fg2, modifier = Modifier.widthIn(max = 700.dp))
                    }
                    if (bankExhausted) {
                        Text(
                            "No unseen excerpts left, so the reading measurement stops here.",
                            color = Amber2,
                            modifier = Modifier.widthIn(max = 700.dp),
                        )
                    }

                    // Standing state, two lines. The READ is the headline because
                    // it is the only number here that is not self-graded.
                    Text(
                        "Reading: " + (lastProbe ?: "not measured yet"),
                        color = Dim2,
                    )
                    Text(
                        "Basics: $solid of ${rows.size} solid · " +
                            t.sittingFinishLabel().replaceFirstChar { it.lowercase() },
                        color = Dim2,
                    )
                    if (!logging) {
                        // Answers "do I need logs on?" without having to ask.
                        Text(
                            "Remote logging is OFF — results stay on the tablet and the build cannot see them.",
                            color = Amber2,
                            modifier = Modifier.widthIn(max = 700.dp),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedButton(onClick = { showDetails = !showDetails }) {
                            Text(if (showDetails) "Hide detail" else "Detail")
                        }
                        OutlinedButton(onClick = { showBrief = !showBrief }) {
                            Text(if (showBrief) "Hide brief" else "What am I testing?")
                        }
                        OutlinedButton(onClick = onCompleted) { Text("Go to song") }
                    }

                    if (showBrief) {
                        // The app carries the ask instead of the learner having to
                        // come and ask for it. Blunt and mechanical (SPEC §2.8).
                        Column(
                            modifier = Modifier.widthIn(max = 760.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Build ${TestingBrief.BUILD}", color = Dim2)
                            Text("Do this", color = Amber2)
                            TestingBrief.doThis.forEach { Text("· $it", color = Fg2) }
                            Text("What it decides", color = Amber2)
                            TestingBrief.feeds.forEach { Text("· $it", color = Fg2) }
                            Text("How long", color = Amber2)
                            Text(TestingBrief.howLong, color = Fg2)
                            Text(TestingBrief.blocking, color = Fg2)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "Remote logging: " + if (logging) "ON" else "OFF",
                                    color = if (logging) Mint2 else Amber2,
                                )
                                OutlinedButton(onClick = {
                                    logging = !logging
                                    RemoteLog.setEnabled(context, logging)
                                }) { Text(if (logging) "Turn off" else "Turn on") }
                            }
                            Text(TestingBrief.logsWhy, color = Dim2)
                        }
                    }

                    if (showDetails) {
                        Text(
                            "hits today / needed · separate days / needed. " +
                                "A key is solid at 3 hits on 3 different days with no help.",
                            color = Dim2,
                            modifier = Modifier.widthIn(max = 700.dp),
                        )
                        rows.forEach { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (row.mastered) "✓ " else if (row.atCriterion) "· " else "  ") +
                                        row.label,
                                    Modifier.width(240.dp),
                                    color = if (row.mastered) Mint2 else Fg2,
                                )
                                Text(
                                    "${row.hitsToday}/${row.hitsWanted}   " +
                                        "${row.daysCredited}/${row.daysWanted} days" +
                                        (if (row.rung > 0) "   +${row.rung} oct" else ""),
                                    color = Dim2,
                                )
                            }
                        }
                    }
                }
            }
        }
        BackText(onExit)
    }
}

/** The one thing to do next, and what it costs. Never more than one. */
private class FoundationsAction(
    val title: String,
    val detail: String,
    val button: String?,
    val onClick: () -> Unit = {},
)

/** Which path the learner was last on. Resumption, not a gate. */
const val PREF_LAST_PATH = "last_path"
const val PATH_BASICS = "basics"
const val PATH_SONG = "song"
private const val SETTING_KEY = "foundations_trainer_state"
private val Mint2 = Color(0xFF00E676)
private val Amber2 = Color(0xFFFFB74D)
private val Fg2 = Color(0xFFC8D2D7)
private val Dim2 = Color(0xFF78828C)
