package com.fingerly.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationsTrainerTest {

    private fun hit(p: FoundationsTrainer.Prompt, latency: Int = 900) =
        FoundationsTrainer.PromptResult(p.atomId, true, false, latency, p.midiNote, emptyList())

    private fun missWith(p: FoundationsTrainer.Prompt, wrong: List<Int>) =
        FoundationsTrainer.PromptResult(p.atomId, false, false, 3000, p.midiNote, wrong)

    /** Run one perfect drill on day [day]; returns the drill that was run. */
    private fun perfectDrill(t: FoundationsTrainer, day: Int): FoundationsTrainer.Drill? {
        val d = t.previewDrill() ?: return null
        t.startDrill(d)
        t.recordResults(d.prompts.map { hit(it) }, day)
        return d
    }

    @Test
    fun previewIsPureAndStartCommits() {
        val t = FoundationsTrainer()
        val a = t.previewDrill()!!
        val b = t.previewDrill()!!
        // Repeated previews (e.g. recomposition) must not advance anything.
        assertEquals(a.prompts.map { it.midiNote }, b.prompts.map { it.midiNote })
        assertEquals(a.focusAtom, b.focusAtom)
        t.startDrill(a)
        val c = t.previewDrill()!!
        assertTrue(c.prompts.map { it.midiNote } != a.prompts.map { it.midiNote })
    }

    @Test
    fun rungZeroStaysInTheHomeOctaveAndPromptsDropTheOctaveDigit() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        d.prompts.forEach { p ->
            if (p.atomId != "octaves") {
                assertTrue("note ${p.midiNote} outside home octave", p.midiNote in 60..71)
                assertFalse("label ${p.label} leaks octave at rung 0", p.label.any { it.isDigit() })
            }
        }
    }

    @Test
    fun octavesAtomAlwaysSpansMoreThanOneOctave() {
        // An octave-discrimination item that only ever samples one octave would
        // certify the skill without testing it.
        val t = FoundationsTrainer()
        val seen = HashSet<Int>()
        repeat(40) {
            val d = t.previewDrill() ?: return@repeat
            t.startDrill(d)
            d.prompts.filter { it.atomId == "octaves" }.forEach { seen.add(it.midiNote / 12) }
            t.recordResults(d.prompts.map { hit(it) }, dayIndex = it)
        }
        assertTrue("octaves sampled only ${seen.size} octave(s)", seen.size >= 2)
    }

    @Test
    fun tipShownExactlyOncePerAtom() {
        val t = FoundationsTrainer()
        val first = t.previewDrill()!!
        assertNotNull(first.tip)
        t.startDrill(first)
        t.recordResults(first.prompts.map { hit(it) }, 0)
        // Same atom later must not repeat its intro tip.
        var repeats = 0
        repeat(10) { day ->
            val d = t.previewDrill() ?: return@repeat
            if (d.focusAtom == first.focusAtom && d.tip != null) repeats++
            t.startDrill(d)
            t.recordResults(d.prompts.map { hit(it) }, day)
        }
        assertEquals(0, repeats)
    }

    @Test
    fun sessionCriterionRefusesExtraRepsSameDay() {
        val t = FoundationsTrainer()
        // Drill the same day until nothing is eligible — the app must refuse
        // overlearning past criterion rather than serve busywork.
        var guard = 0
        while (t.previewDrill() != null && guard < 60) {
            perfectDrill(t, day = 0)
            guard++
        }
        assertNull(t.previewDrill())
        assertTrue(t.sittingFinishLabel().contains("brought to criterion"))
        // A new day re-opens exactly the post-criterion quota.
        t.startSitting(1)
        assertNotNull(t.previewDrill())
    }

    @Test
    fun masteryRequiresSpacedDaysAndEvidenceBeyondHomeOctave() {
        val t = FoundationsTrainer()
        // Everything perfect, all on ONE day: criterion yes, mastery no.
        var guard = 0
        while (t.previewDrill() != null && guard < 60) {
            perfectDrill(t, day = 0)
            guard++
        }
        assertTrue(t.atoms.values.any { it.atCriterion() })
        assertFalse("one day cannot be mastery", t.allMastered())

        // Spread perfect work across many days; rungs climb, days accumulate.
        for (day in 1..12) {
            t.startSitting(day)
            var inner = 0
            while (t.previewDrill() != null && inner < 20) {
                perfectDrill(t, day)
                inner++
            }
        }
        assertTrue("expected mastery after spaced perfect practice", t.allMastered())
        t.atoms.forEach { (id, st) ->
            assertTrue("$id lacks off-home-octave evidence", st.hitsBeyondHomeOctave() >= 1)
            assertTrue("$id lacks spaced days", st.daysCredited >= 3)
        }
        assertTrue(t.report().contains("separate days"))
    }

    @Test
    fun rungNeverExceedsConfiguredCap() {
        val t = FoundationsTrainer()
        for (day in 0..30) {
            t.startSitting(day)
            var inner = 0
            while (t.previewDrill() != null && inner < 20) {
                perfectDrill(t, day)
                inner++
            }
        }
        t.atoms.forEach { (id, st) ->
            assertTrue("$id rung ${st.rung} above cap", st.rung <= t.config.maxRung)
        }
        // maxRung 2 keeps prompts within C3..B5 — C2 is out of a beginner's reach.
        val d = t.previewDrill()
        d?.prompts?.forEach { assertTrue(it.midiNote in 48..83) }
    }

    @Test
    fun rungStepsNeedAnHonestDenominator() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        // A single padding prompt (1/1 = 100%) must not ratchet a rung.
        val padAtom = d.prompts.map { it.atomId }.first { it != d.focusAtom }
        val onePad = d.prompts.first { it.atomId == padAtom }
        t.recordResults(listOf(hit(onePad)), 0)
        assertEquals(0, t.atoms.getValue(padAtom).rung)
    }

    @Test
    fun errorTaxonomyUsesWhiteKeyGeography() {
        // Octave error: right letter, wrong octave.
        assertEquals(FoundationsTrainer.ERROR_OCTAVE, FoundationsTrainer.classifyError(60, 72))
        assertEquals(FoundationsTrainer.ERROR_OCTAVE, FoundationsTrainer.classifyError(60, 48))
        // Adjacent WHITE key is a neighbor error even across a 2-semitone gap.
        assertEquals(FoundationsTrainer.ERROR_NEIGHBOR, FoundationsTrainer.classifyError(60, 62))
        assertEquals(FoundationsTrainer.ERROR_NEIGHBOR, FoundationsTrainer.classifyError(64, 65))
        // E→G is two white keys away: not a neighbor.
        assertEquals(FoundationsTrainer.ERROR_OTHER, FoundationsTrainer.classifyError(64, 67))
        // Far miss.
        assertEquals(FoundationsTrainer.ERROR_OTHER, FoundationsTrainer.classifyError(60, 77))
    }

    @Test
    fun errorRatesDecayInsteadOfAccumulating() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        val focus = d.prompts.first { it.atomId == d.focusAtom }
        // Three octave errors early…
        repeat(3) { t.recordResults(listOf(missWith(focus, listOf(focus.midiNote + 12))), 0) }
        val peak = t.atoms.getValue(d.focusAtom).octaveRate
        assertTrue(peak > 0f)
        // …then clean work: the old behavior must fade, not fossilize.
        repeat(15) { t.recordResults(listOf(hit(focus)), 0) }
        assertTrue(
            "octave error rate did not decay (was $peak, now ${t.atoms.getValue(d.focusAtom).octaveRate})",
            t.atoms.getValue(d.focusAtom).octaveRate < peak / 2f,
        )
    }

    @Test
    fun revealedPromptsEarnNothing() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        val revealed = d.prompts.map {
            FoundationsTrainer.PromptResult(it.atomId, false, true, 5000, it.midiNote, emptyList())
        }
        t.recordResults(revealed, 0)
        assertTrue(t.atoms.values.all { it.totalHits() == 0 })
        assertTrue(t.atoms.values.all { it.daysCredited == 0 })
    }

    @Test
    fun spacedDayCreditNeedsTheDaysFirstAttempt() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        val focus = d.prompts.first { it.atomId == d.focusAtom }
        // First attempt of the day fails, later attempt succeeds: no day credit —
        // the credit means "recalled it cold", not "got it eventually".
        t.recordResults(listOf(missWith(focus, listOf(focus.midiNote + 1)), hit(focus)), 5)
        assertEquals(0, t.atoms.getValue(d.focusAtom).daysCredited)

        // Clean cold probe on a new day does earn it.
        t.startSitting(6)
        t.recordResults(listOf(hit(focus)), 6)
        assertEquals(1, t.atoms.getValue(d.focusAtom).daysCredited)
    }

    @Test
    fun songGateOpensOnCoreLettersOnly() {
        val t = FoundationsTrainer()
        assertFalse(t.songGateOpen())
        for (id in FoundationsTrainer.SONG_GATE_ATOMS) {
            val st = t.atoms.getValue(id)
            repeat(t.config.sessionCriterionHits) { st.hitsAtRung[0]++ }
        }
        assertTrue("core letters at criterion must unlock songs", t.songGateOpen())
        assertFalse("…without implying full mastery", t.allMastered())
    }

    @Test
    fun sameAtomIsNotMassedBackToBack() {
        val t = FoundationsTrainer()
        var adjacent = 0
        var total = 0
        repeat(12) { day ->
            val d = t.previewDrill() ?: return@repeat
            t.startDrill(d)
            d.prompts.zipWithNext().forEach { (a, b) ->
                total++
                if (a.atomId == b.atomId) adjacent++
            }
            t.recordResults(d.prompts.map { hit(it) }, day)
        }
        // Light spacing: with 5 focus prompts in 8 slots some adjacency is
        // unavoidable, but it must not be the norm.
        assertTrue("massed $adjacent/$total adjacent pairs", adjacent * 2 < total)
    }

    @Test
    fun serializationRoundTripsIncludingDaysAndRungs() {
        val t = FoundationsTrainer()
        for (day in 0..4) {
            t.startSitting(day)
            var inner = 0
            while (t.previewDrill() != null && inner < 10) {
                perfectDrill(t, day)
                inner++
            }
        }
        val restored = FoundationsTrainer(t.serialize())
        assertEquals(t.serialize(), restored.serialize())
        t.atoms.forEach { (id, st) ->
            val r = restored.atoms.getValue(id)
            assertEquals(st.daysCredited, r.daysCredited)
            assertEquals(st.rung, r.rung)
            assertEquals(st.totalHits(), r.totalHits())
            assertEquals(st.mastered(), r.mastered())
        }
    }

    @Test
    fun fingeringAtomsAreGoneBecauseMidiCannotSeeFingers() {
        val ids = FoundationsTrainer().atoms.keys
        assertFalse(ids.contains("rh-position"))
        assertFalse(ids.contains("lh-position"))
        assertEquals(8, ids.size) // 7 letters + octave numbers
    }

    @Test
    fun masteryRowsReportMonotoneCounters() {
        val t = FoundationsTrainer()
        perfectDrill(t, day = 0)
        val rows = t.masteryRows()
        assertEquals(8, rows.size)
        rows.forEach { row ->
            assertTrue(row.hitsWanted > 0)
            assertEquals(t.config.criterionDays, row.daysWanted)
            assertTrue(row.hitsToday >= 0)
        }
    }
}
