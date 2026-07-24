package com.keyquest.app.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keyquest.app.midi.MidiEngine
import kotlinx.coroutines.delay

/**
 * First-run checklist for HyperOS gotchas (SPEC §1). Each item is verifiable where
 * the platform allows; states auto-refresh once per second while the screen is open.
 */
@Composable
fun ChecklistScreen(
    engine: MidiEngine,
    currentRefreshRate: () -> Float,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val connection by engine.connectionState.collectAsState()

    var batteryExcluded by remember { mutableStateOf(false) }
    var refreshRate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(PowerManager::class.java)
        while (true) {
            batteryExcluded = pm.isIgnoringBatteryOptimizations(context.packageName)
            refreshRate = currentRefreshRate()
            delay(1_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Setup checklist", style = MaterialTheme.typography.headlineMedium)

            ChecklistItem(
                ok = batteryExcluded,
                title = "Battery optimization: off for KeyQuest",
                detail = "HyperOS throttles background threads. MIDI input needs the exclusion.",
                actionLabel = "Request exclusion",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            ChecklistItem(
                ok = refreshRate >= 120f,
                title = "Per-app refresh rate: 144Hz (currently ${refreshRate.toInt()}Hz)",
                detail = "HyperOS may lock third-party apps to 60Hz. " +
                    "Settings → Display → Refresh rate → KeyQuest → 144.",
                actionLabel = "Open app settings",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            ChecklistItem(
                ok = connection.connected,
                title = if (connection.connected) {
                    "Piano connected: ${connection.deviceName ?: "USB MIDI device"}"
                } else {
                    "Piano: not connected"
                },
                detail = "Connect the piano over USB-C. Detected automatically.",
                actionLabel = null,
                onAction = {},
            )

            Button(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun ChecklistItem(
    ok: Boolean,
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(640.dp)) {
            StatusLine(ok = ok, text = title)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color(0xFF78828C),
            )
        }
        if (actionLabel != null && !ok) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
