package com.fingerly.core.song

import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * MusicXML (score-partwise) → [Score]. First-class import format (SPEC §5).
 *
 * Supported: pitch (step/alter/octave), divisions, chords, rests, ties,
 * backup/forward, multiple staves (staff 1 → right hand, others → left),
 * time signature, tempo from <sound tempo>. Simplifications for now: the first
 * tempo found applies to the whole piece; grace notes are skipped; only the
 * first <part> is read (piano parts are a single part with two staves).
 *
 * Uses DOM (org.w3c.dom) — available on both desktop JVM (unit tests) and
 * Android. Not a hot path; parses happen at load time only.
 */
object MusicXmlParser {

    private val STEP_SEMITONES = mapOf(
        "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
    )

    fun parse(input: InputStream): Score {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        // MusicXML files carry a DOCTYPE pointing at musicxml.org; never fetch it.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val doc = builder.parse(input)

        val title = doc.getElementsByTagName("work-title").item(0)?.textContent
            ?: doc.getElementsByTagName("movement-title").item(0)?.textContent
            ?: "Untitled"

        val part = doc.getElementsByTagName("part").let { parts ->
            (0 until parts.length).map { parts.item(it) as Element }
                .firstOrNull { it.getElementsByTagName("measure").length > 0 }
        } ?: throw IllegalArgumentException("no <part> with measures found")

        var divisions = 1
        var tempoBpm = 0.0
        var beatsPerBar = 4
        var measureStartSec = 0.0
        val notes = ArrayList<ChartNote>()
        // Open tied notes waiting for their tie stop, keyed by (midi shl 4) or staff.
        val openTies = HashMap<Int, ChartNote>()

        val measures = part.getElementsByTagName("measure")
        for (m in 0 until measures.length) {
            val measure = measures.item(m) as Element
            var cursorDivs = 0L
            var maxCursorDivs = 0L
            var lastNoteStartDivs = 0L

            var child = measure.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE) {
                    val el = child as Element
                    when (el.tagName) {
                        "attributes" -> {
                            el.firstText("divisions")?.toIntOrNull()?.let { divisions = it }
                            el.firstText("beats")?.toIntOrNull()?.let { beatsPerBar = it }
                        }

                        "direction", "sound" -> {
                            val sound = if (el.tagName == "sound") {
                                el
                            } else {
                                el.getElementsByTagName("sound").item(0) as Element?
                            }
                            val t = sound?.getAttribute("tempo")?.toDoubleOrNull()
                            if (t != null && t > 0 && tempoBpm == 0.0) tempoBpm = t
                        }

                        "backup" -> {
                            cursorDivs -= el.firstText("duration")?.toLongOrNull() ?: 0L
                        }

                        "forward" -> {
                            cursorDivs += el.firstText("duration")?.toLongOrNull() ?: 0L
                            if (cursorDivs > maxCursorDivs) maxCursorDivs = cursorDivs
                        }

                        "note" -> {
                            val dur = el.firstText("duration")?.toLongOrNull()
                            if (dur != null) { // grace notes have no duration: skip
                                val isChord = el.hasDirectChild("chord")
                                val startDivs = if (isChord) lastNoteStartDivs else cursorDivs
                                if (!isChord) {
                                    lastNoteStartDivs = cursorDivs
                                    cursorDivs += dur
                                    if (cursorDivs > maxCursorDivs) maxCursorDivs = cursorDivs
                                }
                                if (!el.hasDirectChild("rest")) {
                                    val bpm = if (tempoBpm > 0) tempoBpm else DEFAULT_TEMPO
                                    val secPerDiv = 60.0 / (bpm * divisions)
                                    emitNote(
                                        el, startDivs, dur, secPerDiv, measureStartSec,
                                        m + 1, notes, openTies,
                                    )
                                }
                            }
                        }
                    }
                }
                child = child.nextSibling
            }

            val bpm = if (tempoBpm > 0) tempoBpm else DEFAULT_TEMPO
            val measureDivs =
                if (maxCursorDivs > 0) maxCursorDivs else (beatsPerBar * divisions).toLong()
            measureStartSec += measureDivs * 60.0 / (bpm * divisions)
        }

        notes.sortWith(compareBy({ it.startSeconds }, { it.midiNote }))
        val total = notes.maxOfOrNull { it.startSeconds + it.durationSeconds } ?: 0.0
        return Score(
            title = title,
            notes = notes,
            tempoBpm = if (tempoBpm > 0) tempoBpm else DEFAULT_TEMPO,
            beatsPerBar = beatsPerBar,
            totalSeconds = total,
        )
    }

    private fun emitNote(
        el: Element,
        startDivs: Long,
        durDivs: Long,
        secPerDiv: Double,
        measureStartSec: Double,
        measureNumber: Int,
        notes: MutableList<ChartNote>,
        openTies: MutableMap<Int, ChartNote>,
    ) {
        val step = el.firstText("step") ?: return
        val octave = el.firstText("octave")?.toIntOrNull() ?: return
        val alter = el.firstText("alter")?.toIntOrNull() ?: 0
        val semitone = STEP_SEMITONES[step] ?: return
        val midi = (octave + 1) * 12 + semitone + alter
        val staff = el.firstText("staff")?.toIntOrNull() ?: 1
        val hand = if (staff <= 1) ChartNote.HAND_RIGHT else ChartNote.HAND_LEFT

        var tieStart = false
        var tieStop = false
        var tie = el.firstChild
        while (tie != null) {
            if (tie.nodeType == Node.ELEMENT_NODE && (tie as Element).tagName == "tie") {
                when (tie.getAttribute("type")) {
                    "start" -> tieStart = true
                    "stop" -> tieStop = true
                }
            }
            tie = tie.nextSibling
        }

        val key = midi * 8 + staff
        val durSec = durDivs * secPerDiv
        if (tieStop) {
            val open = openTies.remove(key)
            if (open != null) {
                open.durationSeconds += durSec
                if (tieStart) openTies[key] = open // middle of a tie chain
                return
            }
            // tie stop without a matching start: fall through, treat as new note
        }
        val note = ChartNote(
            midiNote = midi,
            startSeconds = measureStartSec + startDivs * secPerDiv,
            durationSeconds = durSec,
            hand = hand,
            measure = measureNumber,
        )
        notes.add(note)
        if (tieStart) openTies[key] = note
    }

    private const val DEFAULT_TEMPO = 120.0

    /** Text of the first descendant with [tag], or null. */
    private fun Element.firstText(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()

    private fun Element.hasDirectChild(tag: String): Boolean {
        var c = firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE && (c as Element).tagName == tag) return true
            c = c.nextSibling
        }
        return false
    }
}

/** Bundled songs, shipped as resources inside the :core jar. */
object BundledSongs {

    fun gymnopedie1Excerpt(): Score = load("songs/gymnopedie1_excerpt.musicxml")

    /**
     * The absolute-beginner starter (SPEC §2: hands on keys in minute one):
     * C-position five-finger melody, stepwise, LH whole-note roots.
     */
    fun odeToJoyBeginner(): Score = load("songs/ode_to_joy_beginner.musicxml")

    private fun load(path: String): Score =
        MusicXmlParser.parse(
            requireNotNull(
                BundledSongs::class.java.classLoader?.getResourceAsStream(path),
            ) { "bundled song resource missing: $path" },
        )
}
