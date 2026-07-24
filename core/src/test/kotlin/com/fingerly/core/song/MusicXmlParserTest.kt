package com.fingerly.core.song

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlParserTest {

    private fun parse(xml: String): Score =
        MusicXmlParser.parse(ByteArrayInputStream(xml.toByteArray()))

    private fun wrap(measures: String, divisions: Int = 2, tempo: Int = 60): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="4.0">
          <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>$divisions</divisions>
                <time><beats>3</beats><beat-type>4</beat-type></time><staves>2</staves>
              </attributes>
              <direction><sound tempo="$tempo"/></direction>
              $measures
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    private fun note(
        step: String,
        octave: Int,
        dur: Int,
        alter: Int = 0,
        staff: Int = 1,
        chord: Boolean = false,
        tie: String? = null,
    ): String {
        val sb = StringBuilder("<note>")
        if (chord) sb.append("<chord/>")
        sb.append("<pitch><step>$step</step>")
        if (alter != 0) sb.append("<alter>$alter</alter>")
        sb.append("<octave>$octave</octave></pitch>")
        sb.append("<duration>$dur</duration>")
        if (tie != null) sb.append("<tie type=\"$tie\"/>")
        sb.append("<staff>$staff</staff></note>")
        return sb.toString()
    }

    @Test
    fun pitchAndTimingBasics() {
        // C4 quarter, D4 quarter at 60bpm, divisions=2 → 1s each.
        val score = parse(wrap(note("C", 4, 2) + note("D", 4, 2)))
        assertEquals(2, score.notes.size)
        assertEquals(60, score.notes[0].midiNote) // C4
        assertEquals(62, score.notes[1].midiNote) // D4
        assertEquals(0.0, score.notes[0].startSeconds, 1e-9)
        assertEquals(1.0, score.notes[1].startSeconds, 1e-9)
        assertEquals(1.0, score.notes[0].durationSeconds, 1e-9)
        assertEquals(60.0, score.tempoBpm, 1e-9)
        assertEquals(3, score.beatsPerBar)
    }

    @Test
    fun alterProducesSharp() {
        val score = parse(wrap(note("F", 5, 2, alter = 1)))
        assertEquals(78, score.notes[0].midiNote) // F#5
    }

    @Test
    fun chordNotesShareStartTime() {
        val m = note("C", 4, 4) + note("E", 4, 4, chord = true) + note("G", 4, 4, chord = true)
        val score = parse(wrap(m))
        assertEquals(3, score.notes.size)
        score.notes.forEach { assertEquals(0.0, it.startSeconds, 1e-9) }
    }

    @Test
    fun restAdvancesTime() {
        val m = "<note><rest/><duration>2</duration><staff>1</staff></note>" + note("C", 4, 2)
        val score = parse(wrap(m))
        assertEquals(1, score.notes.size)
        assertEquals(1.0, score.notes[0].startSeconds, 1e-9)
    }

    @Test
    fun backupOverlapsStaves() {
        val m = note("C", 5, 6) +
            "<backup><duration>6</duration></backup>" +
            note("C", 3, 6, staff = 2)
        val score = parse(wrap(m))
        assertEquals(2, score.notes.size)
        assertEquals(0.0, score.notes[0].startSeconds, 1e-9)
        assertEquals(0.0, score.notes[1].startSeconds, 1e-9)
        assertEquals(ChartNote.HAND_LEFT, score.notes.first { it.midiNote == 48 }.hand)
        assertEquals(ChartNote.HAND_RIGHT, score.notes.first { it.midiNote == 72 }.hand)
    }

    @Test
    fun tieMergesDuration() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"/></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions>
                    <time><beats>3</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <direction><sound tempo="60"/></direction>
                  ${note("A", 4, 6, tie = "start")}
                </measure>
                <measure number="2">${note("A", 4, 6, tie = "stop")}</measure>
              </part>
            </score-partwise>
        """.trimIndent()
        val score = parse(xml)
        assertEquals(1, score.notes.size)
        assertEquals(6.0, score.notes[0].durationSeconds, 1e-9) // two dotted halves @60
        assertEquals(6.0, score.totalSeconds, 1e-9)
    }

    @Test
    fun bundledOdeToJoyIsGenuinelyBeginnerSized() {
        val score = BundledSongs.odeToJoyBeginner()
        assertEquals(80.0, score.tempoBpm, 1e-9)
        assertEquals(4, score.beatsPerBar)
        assertEquals(24.0, score.totalSeconds, 1e-6) // 8 bars @ 3s

        val rh = score.notes.filter { it.hand == ChartNote.HAND_RIGHT }
        val lh = score.notes.filter { it.hand == ChartNote.HAND_LEFT }
        assertEquals(30, rh.size)
        assertEquals(9, lh.size)

        // Melody stays inside the C-position five-finger range (C4..G4)…
        assertTrue(rh.all { it.midiNote in 60..67 })
        // …moves stepwise (no interval larger than a 2nd)…
        rh.zipWithNext().forEach { (a, b) ->
            assertTrue(Math.abs(b.midiNote - a.midiNote) <= 2)
        }
        // …and starts on E4.
        assertEquals(64, rh.first().midiNote)

        // Left hand: only C3/G2 roots, one note at a time.
        assertTrue(lh.all { it.midiNote == 48 || it.midiNote == 43 })
    }

    @Test
    fun bundledGymnopedieExcerptParses() {
        val score = BundledSongs.gymnopedie1Excerpt()
        assertTrue(score.title.startsWith("Gymnopédie No. 1"))
        assertEquals(60.0, score.tempoBpm, 1e-9)
        assertEquals(3, score.beatsPerBar)
        // 14 bars × 4 LH notes + 20 RH notes (two tie pairs merged) = 76.
        assertEquals(76, score.notes.size)
        assertEquals(42.0, score.totalSeconds, 1e-6) // 14 bars @ 3s
        // First LH bass note: G2 at t=0.
        assertEquals(43, score.notes.first().midiNote)
        // Melody starts bar 5 (t=12s) on F#5.
        val firstMelody = score.notes.first { it.hand == ChartNote.HAND_RIGHT }
        assertEquals(78, firstMelody.midiNote)
        assertEquals(12.0, firstMelody.startSeconds, 1e-6)
        // Tied melody note bars 8-9: F#4 for 6 seconds.
        val tied = score.notes.first { it.hand == ChartNote.HAND_RIGHT && it.measure == 8 }
        assertEquals(66, tied.midiNote)
        assertEquals(6.0, tied.durationSeconds, 1e-6)
        // Sorted by start time.
        assertTrue(score.notes.zipWithNext().all { (a, b) -> a.startSeconds <= b.startSeconds })
    }
}
