package com.fingerly.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.fingerly.app.latency.LatencyTestView
import com.fingerly.app.midi.MidiEngine
import com.fingerly.app.data.SongCatalog
import kotlinx.coroutines.flow.StateFlow

enum class Screen { Shell, Checklist, LatencyTest, VirtualPiano, Settings, Highway, Session, Dashboard, Library }

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF00E676),
    background = Color.Black,
    surface = Color(0xFF101418),
)

/**
 * Phase 1 shell. From Phase 3 on, the app opens directly into today's session
 * (SPEC §1/§3); until then it opens into connection status. First run opens the
 * HyperOS checklist (SPEC §1).
 */
@Composable
fun FingerlyApp(
    engine: MidiEngine,
    currentRefreshRate: () -> Float,
    relaunchSignal: StateFlow<Int>? = null,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fingerly", 0) }
    var screen by remember {
        mutableStateOf(
            // SPEC §1: the app opens directly into today's session — no menu
            // navigation to start. First run goes through the checklist once.
            if (prefs.getBoolean("checklist_done", false)) Screen.Session else Screen.Checklist,
        )
    }
    // The current song drives both sessions and free play; picked in Library.
    var songVersion by remember { mutableStateOf(0) }
    val currentSongPath = remember(songVersion) { SongCatalog.currentPath(prefs) }
    val currentScore = remember(songVersion) {
        SongCatalog.load(currentSongPath) ?: SongCatalog.bundled.first().loader()
    }
    val currentMeta = remember(songVersion) { SongCatalog.metaFor(currentSongPath) }

    // Relaunching from the launcher always lands on today's session (SPEC §1),
    // even if the activity was still alive on another screen.
    if (relaunchSignal != null) {
        val relaunches by relaunchSignal.collectAsState()
        LaunchedEffect(relaunches) {
            if (relaunches > 0 && prefs.getBoolean("checklist_done", false)) {
                screen = Screen.Session
            }
        }
    }

    MaterialTheme(colorScheme = DarkScheme) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (screen) {
                Screen.Shell -> ShellScreen(
                    engine = engine,
                    currentRefreshRate = currentRefreshRate,
                    onOpenChecklist = { screen = Screen.Checklist },
                    onOpenLatencyTest = { screen = Screen.LatencyTest },
                    onOpenVirtualPiano = { screen = Screen.VirtualPiano },
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenHighway = { screen = Screen.Highway },
                    onOpenSession = { screen = Screen.Session },
                    onOpenDashboard = { screen = Screen.Dashboard },
                    onOpenLibrary = { screen = Screen.Library },
                    onOpenBasics = {
                        // Switching back to basics is symmetric with going to
                        // songs: it changes the default, it does not lock anything.
                        prefs.edit().putString(PREF_LAST_PATH, PATH_BASICS).apply()
                        screen = Screen.Session
                    },
                    songTitle = currentScore.title,
                )

                Screen.Checklist -> ChecklistScreen(
                    engine = engine,
                    currentRefreshRate = currentRefreshRate,
                    onDone = {
                        prefs.edit().putBoolean("checklist_done", true).apply()
                        screen = Screen.Session
                    },
                )

                Screen.LatencyTest -> LatencyTestScreen(
                    engine = engine,
                    onBack = { screen = Screen.Shell },
                )

                Screen.VirtualPiano -> VirtualPianoScreen(
                    engine = engine,
                    onBack = { screen = Screen.Shell },
                )

                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Shell })

                Screen.Highway -> HighwayScreen(
                    engine = engine,
                    score = currentScore,
                    onBack = { screen = Screen.Shell },
                )

                Screen.Session -> {
                    // NOT a gate. Basics are what the app serves by default, and
                    // the song path is always reachable in one tap — SPEC §4
                    // states note names must be "optional, late, never a gate",
                    // and §4a-F exists because the trainer had made them THE gate.
                    // What survives is resumption: launch lands where practice
                    // stopped, which is the single best-evidenced UI finding in
                    // the record (Ghibellini & Meier: resumption holds,
                    // unfinished-progress framing does not).
                    var path by remember {
                        mutableStateOf(prefs.getString(PREF_LAST_PATH, PATH_BASICS))
                    }
                    fun goTo(next: String) {
                        prefs.edit().putString(PREF_LAST_PATH, next).apply()
                        path = next
                    }
                    if (path != PATH_SONG) {
                        FoundationsScreen(
                            engine = engine,
                            onCompleted = { goTo(PATH_SONG) },
                            onExit = { screen = Screen.Shell },
                        )
                    } else {
                        SessionScreen(
                            engine = engine,
                            score = currentScore,
                            songPath = currentSongPath,
                            composer = currentMeta.second,
                            difficultyRank = currentMeta.third,
                            onExit = { screen = Screen.Shell },
                        )
                    }
                }

                Screen.Dashboard -> DashboardScreen(
                    engine = engine,
                    onBack = { screen = Screen.Shell },
                )

                Screen.Library -> LibraryScreen(
                    onSongChosen = {
                        songVersion++
                        screen = Screen.Shell
                    },
                    onBack = { screen = Screen.Shell },
                )
            }
        }
    }
}

@Composable
private fun LatencyTestScreen(engine: MidiEngine, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> LatencyTestView(ctx, engine.ring) },
        )
        BackText(onBack)
    }
}
