package com.fingerly.app.data

import android.content.Context
import android.content.SharedPreferences
import com.fingerly.core.song.BundledSongs
import com.fingerly.core.song.MusicXmlParser
import com.fingerly.core.song.Score
import java.io.File

/**
 * Resolves song identities (stable filePath keys) to parsed scores — bundled
 * resources or files imported from local storage (SPEC §5).
 */
object SongCatalog {

    const val PREF_CURRENT_SONG = "current_song_path"

    class Entry(
        val filePath: String,
        val title: String,
        val composer: String,
        val difficultyRank: Int,
        val loader: () -> Score,
    )

    val bundled: List<Entry> = listOf(
        Entry(
            "bundled:ode_to_joy_beginner",
            "Ode to Joy (beginner arrangement)", "Ludwig van Beethoven", 0,
        ) { BundledSongs.odeToJoyBeginner() },
        Entry(
            "bundled:bach_prelude_c_excerpt",
            "Prelude in C (BWV 846, excerpt)", "Johann Sebastian Bach", 1,
        ) { BundledSongs.bachPreludeCExcerpt() },
        Entry(
            "bundled:gymnopedie1_excerpt",
            "Gymnopédie No. 1 (excerpt, simplified)", "Erik Satie", 2,
        ) { BundledSongs.gymnopedie1Excerpt() },
    )

    fun currentPath(prefs: SharedPreferences): String =
        prefs.getString(PREF_CURRENT_SONG, bundled.first().filePath)
            ?: bundled.first().filePath

    /** Metadata for a song key; imported files fall back to generic values. */
    fun metaFor(filePath: String): Triple<String, String, Int> =
        bundled.firstOrNull { it.filePath == filePath }
            ?.let { Triple(it.title, it.composer, it.difficultyRank) }
            ?: Triple(File(filePath).nameWithoutExtension, "Imported", 99)

    /** Parses the song, or null if the key is stale (e.g. deleted import). */
    fun load(filePath: String): Score? = runCatching {
        bundled.firstOrNull { it.filePath == filePath }?.loader?.invoke()
            ?: File(filePath).inputStream().use { MusicXmlParser.parse(it) }
    }.getOrNull()

    /** Directory imported MusicXML files are copied into. */
    fun importDir(context: Context): File =
        File(context.filesDir, "songs").apply { mkdirs() }
}
