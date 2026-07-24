package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fingerly.app.highway.NoteHighwayView
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.song.Score

/**
 * Note highway host. The heavy lifting is [NoteHighwayView] (custom render layer);
 * Compose only contributes the overlay controls (SPEC §1 stack split).
 */
@Composable
fun HighwayScreen(engine: MidiEngine, score: Score, onBack: () -> Unit) {
    var view by remember { mutableStateOf<NoteHighwayView?>(null) }
    var perfMode by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> NoteHighwayView(ctx, engine.ring, score).also { view = it } },
        )
        BackText(onBack)
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = {
                perfMode = !perfMode
                view?.perfTestMode = perfMode
            }) { Text(if (perfMode) "Perf test: on" else "Perf test") }
            OutlinedButton(onClick = { view?.restart() }) { Text("Restart") }
        }
    }
}
