package com.fingerly.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.viewinterop.AndroidView
import com.fingerly.app.highway.NoteHighwayView
import com.fingerly.app.log.RemoteLog
import com.fingerly.app.midi.MidiEngine
import com.fingerly.core.session.Foundations

/**
 * Foundations course host (SPEC §2.5/§4): key-finding, letters, octaves and
 * hand position, learned through real key presses in wait mode. Gates the song
 * sessions until completed once; skippable for returning players.
 */
@Composable
fun FoundationsScreen(engine: MidiEngine, onCompleted: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fingerly", 0) }
    val lessons = remember { Foundations.lessons() }

    var lessonIndex by remember {
        mutableStateOf(prefs.getInt(PREF_FOUNDATIONS_INDEX, 0).coerceIn(0, lessons.size))
    }
    var playing by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    fun advance() {
        lessonIndex++
        prefs.edit().putInt(PREF_FOUNDATIONS_INDEX, lessonIndex).apply()
        if (lessonIndex >= lessons.size) onCompleted()
    }

    if (lessonIndex >= lessons.size) {
        onCompleted()
        return
    }
    val lesson = lessons[lessonIndex]

    Box(Modifier.fillMaxSize()) {
        if (!playing) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Foundations ${lessonIndex + 1}/${lessons.size} — ${lesson.title}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    lesson.intro,
                    color = Color(0xFFC8D2D7),
                    modifier = Modifier.widthIn(max = 700.dp),
                )
                if (lastResult != null) Text(lastResult!!, color = Color(0xFF78828C))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        lastResult = null
                        playing = true
                    }) { Text("Start") }
                    OutlinedButton(onClick = {
                        prefs.edit().putInt(PREF_FOUNDATIONS_INDEX, lessons.size).apply()
                        onCompleted()
                    }) { Text("Skip foundations") }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    NoteHighwayView(ctx, engine.ring, lesson.score).apply {
                        tapToRestart = false
                        leadInMs = 1500
                        waitMode = true
                        onEnded = { judge ->
                            val acc = judge.accuracyPercent()
                            RemoteLog.log(
                                "foundations",
                                "${lesson.id} acc=${acc.toInt()}% extras=${judge.extras}",
                            )
                            playing = false
                            if (acc >= Foundations.PASS_ACCURACY) {
                                lastResult = null
                                advance()
                            } else {
                                lastResult =
                                    "${acc.toInt()}% — ${judge.extras} wrong keys. Again."
                            }
                        }
                    }
                },
            )
            Text(
                lesson.title,
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                color = Color(0xFF78828C),
            )
        }
        BackText(onExit)
    }
}

const val PREF_FOUNDATIONS_INDEX = "foundations_index"
