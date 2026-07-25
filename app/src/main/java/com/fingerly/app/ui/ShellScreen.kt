package com.fingerly.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fingerly.app.midi.MidiEngine
import kotlinx.coroutines.delay

/**
 * Phase 1 status shell. Copy is diagnostic, not motivational (SPEC §2.8):
 * states and numbers only.
 */
@Composable
fun ShellScreen(
    engine: MidiEngine,
    currentRefreshRate: () -> Float,
    onOpenChecklist: () -> Unit,
    onOpenLatencyTest: () -> Unit,
    onOpenVirtualPiano: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHighway: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenLibrary: () -> Unit,
    songTitle: String,
) {
    val connection by engine.connectionState.collectAsState()
    var refreshRate by remember { mutableFloatStateOf(currentRefreshRate()) }
    LaunchedEffect(Unit) {
        while (true) {
            refreshRate = currentRefreshRate()
            delay(1_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Fingerly — Phase 1 foundation", style = MaterialTheme.typography.headlineMedium)

            StatusLine(
                ok = connection.connected,
                text = if (connection.connected) {
                    "Piano: ${connection.deviceName ?: "USB MIDI device"}"
                } else {
                    "Piano: not connected. Plug in via USB-C."
                },
            )
            StatusLine(
                ok = refreshRate >= 120f,
                text = if (refreshRate >= 120f) {
                    "Display: ${refreshRate.toInt()}Hz"
                } else {
                    "Display: ${refreshRate.toInt()}Hz — set per-app refresh to 120 (checklist)"
                },
            )

            Button(onClick = onOpenSession) { Text("Today's session") }
            OutlinedButton(onClick = onOpenDashboard) { Text("Progress") }
            OutlinedButton(onClick = onOpenLibrary) { Text("Songs  ·  now: $songTitle") }
            OutlinedButton(onClick = onOpenHighway) { Text("Free play") }
            OutlinedButton(onClick = onOpenChecklist) { Text("Setup checklist") }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            OutlinedButton(onClick = onOpenVirtualPiano) { Text("Virtual piano (demo)") }
            // Available in release too: the tablet installs release builds via
            // Obtainium, and the SPEC §7 acceptance gate must be runnable there.
            Button(onClick = onOpenLatencyTest) { Text("Latency test") }
        }
    }
}

@Composable
fun StatusLine(ok: Boolean, text: String) {
    Text(
        text = (if (ok) "● " else "○ ") + text,
        color = if (ok) Color(0xFF00E676) else Color(0xFFFF5252),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
fun BoxScope.BackText(onBack: () -> Unit) {
    Text(
        "← back",
        color = Color(0xFF78828C),
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(24.dp)
            .clickable { onBack() },
    )
}
