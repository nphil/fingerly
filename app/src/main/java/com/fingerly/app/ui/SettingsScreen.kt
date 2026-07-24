package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fingerly.app.log.RemoteLog

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var remoteLogging by remember { mutableStateOf(RemoteLog.isEnabled()) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(560.dp)) {
                    Text(
                        "Remote debug log",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFF5F5F0),
                    )
                    Text(
                        "Publishes app events to ntfy.sh/${RemoteLog.TOPIC} in one batched " +
                            "request every 30s. Topic is public; no personal data is logged. " +
                            "Leave off outside testing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF78828C),
                    )
                }
                Switch(
                    checked = remoteLogging,
                    onCheckedChange = {
                        remoteLogging = it
                        RemoteLog.setEnabled(context, it)
                    },
                )
            }
        }
        BackText(onBack)
    }
}
