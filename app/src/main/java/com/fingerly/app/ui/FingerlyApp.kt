package com.fingerly.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import com.fingerly.core.song.BundledSongs

enum class Screen { Shell, Checklist, LatencyTest, VirtualPiano, Settings, Highway, Session }

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
    // Parse in ~ms from bundled resources; fine at first composition.
    // Sessions teach the beginner starter; free play shows the goal piece.
    val sessionScore = remember { BundledSongs.odeToJoyBeginner() }
    val freePlayScore = remember { BundledSongs.gymnopedie1Excerpt() }

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
                    songTitle = freePlayScore.title,
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
                    score = freePlayScore,
                    onBack = { screen = Screen.Shell },
                )

                Screen.Session -> SessionScreen(
                    engine = engine,
                    score = sessionScore,
                    onExit = { screen = Screen.Shell },
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
