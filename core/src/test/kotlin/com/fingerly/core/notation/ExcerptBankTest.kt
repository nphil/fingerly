package com.fingerly.core.notation

import com.fingerly.core.song.ChartNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcerptBankTest {

    @Test
    fun oneTokenPerBeatWithExtensionAndRest() {
        val notes = ExcerptBank.parseLine("C4 - E4 . | G4 - - -", ChartNote.HAND_RIGHT)
        assertEquals(3, notes.size)
        // C4 held two beats from beat 0.
        assertEquals(60, notes[0].midi)
        assertEquals(0.0, notes[0].startBeat, 1e-9)
        assertEquals(2.0, notes[0].durationBeats, 1e-9)
        // E4 one beat at beat 2; the rest consumes beat 3.
        assertEquals(64, notes[1].midi)
        assertEquals(2.0, notes[1].startBeat, 1e-9)
        assertEquals(1.0, notes[1].durationBeats, 1e-9)
        // G4 is a whole note starting on the second bar's downbeat.
        assertEquals(67, notes[2].midi)
        assertEquals(4.0, notes[2].startBeat, 1e-9)
        assertEquals(4.0, notes[2].durationBeats, 1e-9)
    }

    @Test
    fun barlinesAreCheckedNotSounded() {
        val withBars = ExcerptBank.parseLine("C4 D4 E4 F4 | G4 - - -", ChartNote.HAND_RIGHT)
        val without = ExcerptBank.parseLine("C4 D4 E4 F4 G4 - - -", ChartNote.HAND_RIGHT)
        assertEquals(without.size, withBars.size)
        for (i in without.indices) {
            assertEquals(without[i].midi, withBars[i].midi)
            assertEquals(without[i].startBeat, withBars[i].startBeat, 1e-9)
        }
    }

    @Test
    fun everyExcerptIsFourBarsOfFourFour() {
        for (e in ExcerptBank.all) {
            assertEquals(e.id, 16.0, e.totalBeats, 1e-9)
            for (n in e.notes) {
                assertTrue(
                    "${e.id}: note at ${n.startBeat} runs past the end",
                    n.startBeat + n.durationBeats <= e.totalBeats + 1e-9,
                )
            }
        }
    }

    @Test
    fun everyPitchIsInsideTheSpanTheModuleTrains() {
        for (e in ExcerptBank.all) {
            for (n in e.notes) {
                assertTrue(
                    "${e.id}: ${n.midi} outside G2–G4",
                    n.midi in ExcerptBank.SPAN_LOW..ExcerptBank.SPAN_HIGH,
                )
            }
        }
    }

    @Test
    fun theBankNeedsNoNotationTheRendererCannotDraw() {
        // No accidentals, and no duration shorter than a beat: the renderer
        // draws quarters, halves and wholes, and nothing else.
        val out = IntArray(Staff.MAX_LEDGER_LINES)
        for (e in ExcerptBank.all) {
            for (n in e.notes) {
                assertTrue("${e.id}: ${n.midi} needs an accidental", !Staff.needsSharp(n.midi))
                assertTrue("${e.id}: duration ${n.durationBeats}", n.durationBeats >= 1.0)
                val clef = if (n.hand == ChartNote.HAND_LEFT) Staff.CLEF_BASS else Staff.CLEF_TREBLE
                assertTrue(
                    "${e.id}: ${n.midi} needs too many ledger lines",
                    Staff.ledgerLines(n.midi, clef, out) <= 2,
                )
            }
        }
    }

    @Test
    fun handsTogetherExcerptsAreActuallyObservableAsTwoHands() {
        // MIDI carries pitch, not hands. Parts closer than 14 semitones could
        // have been played with one hand, so they cannot evidence anything —
        // the same reasoning that deleted the fingering atoms.
        val together = ExcerptBank.all.filter { it.handsTogether }
        assertTrue("the bank must contain hands-together material", together.size >= 8)
        for (e in together) {
            assertTrue(
                "${e.id}: only ${e.scorableHandsTogetherOnsets()} separable onsets",
                e.scorableHandsTogetherOnsets() >= 4,
            )
        }
    }

    @Test
    fun singleHandExcerptsScoreNoHandsTogetherCredit() {
        for (e in ExcerptBank.all.filter { !it.handsTogether }) {
            assertEquals(e.id, 0, e.scorableHandsTogetherOnsets())
            assertTrue(e.notes.none { it.hand == ChartNote.HAND_LEFT })
        }
    }

    @Test
    fun aCloselyVoicedPairEarnsNoHandsTogetherCredit() {
        // C3 under C4 is twelve semitones — one hand can reach it, so it must
        // not count, even though both parts genuinely sound.
        val close = ExcerptBank.Excerpt(
            "probe-close", ExcerptBank.TIER_HANDS_SUSTAINED,
            ExcerptBank.parseLine("C4 - - -", ChartNote.HAND_RIGHT) +
                ExcerptBank.parseLine("C3 - - -", ChartNote.HAND_LEFT),
        )
        assertEquals(0, close.scorableHandsTogetherOnsets())

        val wide = ExcerptBank.Excerpt(
            "probe-wide", ExcerptBank.TIER_HANDS_SUSTAINED,
            ExcerptBank.parseLine("D4 - - -", ChartNote.HAND_RIGHT) +
                ExcerptBank.parseLine("C3 - - -", ChartNote.HAND_LEFT),
        )
        assertEquals(1, wide.scorableHandsTogetherOnsets())
    }

    @Test
    fun densityIsGradedSoAFlatResultIsInterpretable() {
        // If every excerpt were hard the probe could floor at zero for weeks,
        // and a floored instrument cannot be told apart from atoms that do not
        // transfer — which is the one conclusion the falsification check draws.
        val byTier = ExcerptBank.all.groupBy { it.tier }
        for (tier in listOf(
            ExcerptBank.TIER_STEPWISE, ExcerptBank.TIER_SKIPS,
            ExcerptBank.TIER_HANDS_SUSTAINED, ExcerptBank.TIER_HANDS_MOVING,
        )) {
            assertTrue("tier $tier is empty", (byTier[tier]?.size ?: 0) >= 4)
        }
        // The easiest tier is genuinely easy: stepwise, one hand, no leaps.
        for (e in byTier.getValue(ExcerptBank.TIER_STEPWISE)) {
            val rh = e.notes.sortedBy { it.startBeat }
            for (i in 1 until rh.size) {
                assertTrue(
                    "${e.id} leaps ${rh[i].midi - rh[i - 1].midi}",
                    kotlin.math.abs(rh[i].midi - rh[i - 1].midi) <= 2,
                )
            }
        }
    }

    @Test
    fun idsAreUniqueSoConsumedTrackingCannotSilentlyCollide() {
        val ids = ExcerptBank.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun unseenSelectionWalksTheBankEasiestFirstAndThenRunsOut() {
        val consumed = HashSet<String>()
        var lastTier = -1
        repeat(ExcerptBank.all.size) {
            val e = ExcerptBank.nextUnseen(consumed)
            assertNotNull("ran out early at ${consumed.size}", e)
            assertTrue("tier went backwards", e!!.tier >= lastTier)
            lastTier = e.tier
            consumed.add(e.id)
        }
        // Exhaustion must be visible: a second sighting is not a cold read.
        assertNull(ExcerptBank.nextUnseen(consumed))
    }

    @Test
    fun consumedSetSurvivesSerializationAndBankReordering() {
        val consumed = setOf("t0-ode", "t2-tonic")
        val round = ExcerptBank.decodeConsumed(ExcerptBank.encodeConsumed(consumed))
        assertEquals(consumed, round)
        assertEquals(emptySet<String>(), ExcerptBank.decodeConsumed(null))
        assertEquals(emptySet<String>(), ExcerptBank.decodeConsumed(""))
        // Ids, not indices — so adding excerpts never re-serves a seen one.
        for (id in consumed) assertNotNull(ExcerptBank.byId(id))
    }

    @Test
    fun anExcerptBecomesAPlayableScoreAtTheStatedSlowTempo() {
        val e = ExcerptBank.byId("t0-ode")!!
        val score = e.toScore()
        assertEquals(ExcerptBank.COLD_READ_TEMPO_BPM, score.tempoBpm, 1e-9)
        assertEquals(4, score.beatsPerBar)
        assertEquals(e.notes.size, score.notes.size)
        // Sorted by time, and the last note ends when the excerpt ends.
        for (i in 1 until score.notes.size) {
            assertTrue(score.notes[i].startSeconds >= score.notes[i - 1].startSeconds)
        }
        val secPerBeat = 60.0 / ExcerptBank.COLD_READ_TEMPO_BPM
        assertEquals(16.0 * secPerBeat, score.totalSeconds, 1e-9)
        // Measures are 1-based and cover exactly four bars.
        assertEquals(1, score.notes.minOf { it.measure })
        assertEquals(4, score.notes.maxOf { it.measure })
    }

    @Test
    fun pitchAndTimingAreMeasuredSeparately() {
        // Conditions 1 and 3 of the definition of done are different questions.
        // A window narrower than the beat would make a slow-but-correct read
        // indistinguishable from an incorrect one, and the probe would report
        // "cannot read music" about someone who simply read it slowly.
        assertTrue(
            "the pitch window must be generous relative to the beat",
            ExcerptBank.COLD_READ_HIT_WINDOW_MS > ExcerptBank.COLD_READ_BEAT_MS / 2,
        )
        // …but not so wide that a press lands past the NEXT note's onset by more
        // than the judge can resolve; it attributes to the closest match.
        assertTrue(
            ExcerptBank.COLD_READ_HIT_WINDOW_MS < ExcerptBank.COLD_READ_BEAT_MS,
        )
        // A note must never be retired while it is still reachable.
        assertTrue(
            ExcerptBank.COLD_READ_MISS_AFTER_MS > ExcerptBank.COLD_READ_HIT_WINDOW_MS,
        )
        // The count-in is a real bar at the stated tempo, not a round number.
        assertEquals(
            (4 * ExcerptBank.COLD_READ_BEAT_MS).toLong(),
            ExcerptBank.COLD_READ_LEAD_IN_MS,
        )
    }
}
