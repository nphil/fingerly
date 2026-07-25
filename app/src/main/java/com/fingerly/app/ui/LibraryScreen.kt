package com.fingerly.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.fingerly.app.data.FingerlyDatabase
import com.fingerly.app.data.SessionRepository
import com.fingerly.app.data.SongCatalog
import com.fingerly.app.log.RemoteLog
import com.fingerly.core.session.Decomposer
import com.fingerly.core.song.MusicXmlParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Song library (SPEC §5): bundled pieces plus MusicXML imported from local
 * storage. Picking a song sets it as the current session/free-play piece.
 */
@Composable
fun LibraryScreen(onSongChosen: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SessionRepository(FingerlyDatabase.get(context)) }
    val prefs = remember { context.getSharedPreferences("fingerly", 0) }

    var songs by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importStatus = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(
                        SongCatalog.importDir(context),
                        "import_${System.currentTimeMillis()}.musicxml",
                    )
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    val score = dest.inputStream().use { MusicXmlParser.parse(it) }
                    require(score.notes.isNotEmpty()) { "no notes found" }
                    repo.ensureSong(
                        score, dest.absolutePath, "Imported", 99,
                        Decomposer.decompose(score),
                    )
                    RemoteLog.log("library", "imported '${score.title}' (${score.notes.size} notes)")
                    "Imported: ${score.title} (${score.notes.size} notes)"
                }.getOrElse { e ->
                    "Import failed: ${e.message ?: "unreadable MusicXML"}"
                }
            }
            refresh++
        }
    }

    LaunchedEffect(refresh) {
        // Make sure all bundled songs exist in the DB, then list everything.
        for (entry in SongCatalog.bundled) {
            runCatching {
                val score = entry.loader()
                repo.ensureSong(
                    score, entry.filePath, entry.composer, entry.difficultyRank,
                    Decomposer.decompose(score),
                )
            }
        }
        songs = repo.allSongs().map { Triple(it.filePath, it.title, it.composer) }
    }

    val currentPath = SongCatalog.currentPath(prefs)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp, vertical = 64.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Songs", style = MaterialTheme.typography.headlineMedium)
            songs.forEach { (path, title, composer) ->
                val selected = path == currentPath
                Text(
                    text = (if (selected) "▶ " else "") + "$title — $composer",
                    color = if (selected) Color(0xFF00E676) else Color(0xFFC8D2D7),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable {
                        prefs.edit().putString(SongCatalog.PREF_CURRENT_SONG, path).apply()
                        onSongChosen()
                    },
                )
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("*/*"))
            }) { Text("Import MusicXML file") }
            if (importStatus != null) {
                Text(importStatus!!, color = Color(0xFF78828C))
            }
        }
        BackText(onBack)
    }
}
