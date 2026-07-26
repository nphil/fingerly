package com.fingerly.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.fingerly.core.notation.Staff
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
    fun rungZeroAsksForAnyOctaveAndSaysSo() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        d.prompts.forEach { p ->
            // Staff prompts name their pitch by position, so they are exempt:
            // this rule is about the WORDED letter atoms.
            if (p.atomId != FoundationsTrainer.ATOM_OCTAVES &&
                p.render != FoundationsTrainer.RENDER_STAFF
            ) {
                assertTrue("note ${p.midiNote} outside home octave", p.midiNote in 60..71)
                // The prompt must never demand an octave digit the learner has
                // not been taught — and must state the rule it will grade by.
                assertTrue("label '${p.label}' should say 'any'", p.label.startsWith("any "))
                assertFalse("label '${p.label}' leaks an octave digit", p.label.any { it.isDigit() })
                assertTrue(p.matchAnyOctave)
            }
        }
    }

    @Test
    fun octavesAtomAlwaysNamesAndRequiresASpecificOctave() {
        val t = FoundationsTrainer()
        repeat(20) { day ->
            val d = t.previewDrill() ?: return@repeat
            t.startDrill(d)
            d.prompts.filter { it.atomId == FoundationsTrainer.ATOM_OCTAVES }.forEach { p ->
                assertFalse("octaves atom must not accept any octave", p.matchAnyOctave)
                assertTrue("label '${p.label}' must name the octave", p.label.any { it.isDigit() })
            }
            t.recordResults(d.prompts.map { hit(it) }, day)
        }
    }

    @Test
    fun promptsDemandExactOctaveOnceTheLadderWidens() {
        val t = FoundationsTrainer()
        // Climb a letter atom off the home rung.
        var guard = 0
        while (t.atoms.getValue("find-C").rung == 0 && guard < 40) {
            val d = t.previewDrill() ?: break
            t.startDrill(d)
            t.recordResults(d.prompts.map { hit(it) }, guard)
            t.startSitting(guard + 1)
            guard++
        }
        val climbed = t.atoms.entries.filter { it.value.rung > 0 }.map { it.key }
        assertTrue("no atom climbed a rung", climbed.isNotEmpty())
        repeat(10) { day ->
            val d = t.previewDrill() ?: return@repeat
            t.startDrill(d)
            d.prompts.filter { it.atomId in climbed }.forEach { p ->
                assertFalse("rung>0 must require the exact key", p.matchAnyOctave)
                assertTrue("label '${p.label}' must name the octave", p.label.any { it.isDigit() })
            }
            t.recordResults(d.prompts.map { hit(it) }, day + 100)
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
        assertTrue(t.sittingComplete())
        assertTrue(t.sittingFinishLabel().contains("banked today"))
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
            // Pinned-pitch atoms (the staff landmarks) have no octaves to leave.
            if (st.maxRung > 0) {
                assertTrue("$id lacks off-home-octave evidence", st.hitsBeyondHomeOctave() >= 1)
            }
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
    fun nothingIsGatedOnLetterNames() {
        // SPEC §4 — note names are "optional, late, never a gate" — and §4a-F
        // exists because the trainer violated that by making them THE gate.
        // The whole concept is deleted, not merely relaxed.
        val src = java.io.File(
            "src/main/kotlin/com/fingerly/core/session/FoundationsTrainer.kt",
        ).readText()
        assertFalse("songGateOpen must not come back", src.contains("songGateOpen"))
        assertFalse("SONG_GATE_ATOMS must not come back", src.contains("SONG_GATE_ATOMS"))
    }

    @Test
    fun theSittingPaysOutWhetherOrNotItIsExhausted() {
        // The named finish state must be reachable by stopping, which is what
        // actually happens — not only by playing every atom to its quota.
        val t = FoundationsTrainer()
        t.startSitting(0)
        assertTrue(t.sittingFinishLabel().contains("Nothing banked"))
        assertFalse(t.sittingComplete())

        val d = t.previewDrill()!!
        t.startDrill(d)
        t.recordResults(d.prompts.map { hit(it) }, dayIndex = 0)

        val label = t.sittingFinishLabel()
        assertTrue("must name work done, mid-sitting", label.contains("banked today"))
        assertFalse("never a within-session quality score", label.contains("%"))
        assertFalse(t.sittingComplete()) // atoms remain; the label still shows
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
        assertEquals(13, ids.size) // 3 landmarks + span + hands + 7 letters + octaves
    }

    @Test
    fun masteryRowsReportMonotoneCounters() {
        val t = FoundationsTrainer()
        perfectDrill(t, day = 0)
        val rows = t.masteryRows()
        assertEquals(13, rows.size)
        rows.forEach { row ->
            assertTrue(row.hitsWanted > 0)
            assertEquals(t.config.criterionDays, row.daysWanted)
            assertTrue(row.hitsToday >= 0)
        }
    }

    // ---------------------------------------------------------- SPEC §4a-F: staff

    @Test
    fun landmarkAtomsAreNotatedNotWorded() {
        val t = FoundationsTrainer(atomDefs = FoundationsTrainer.defaultAtoms())
        for (id in FoundationsTrainer.LANDMARK_ATOMS) {
            assertTrue("$id must exist", t.atoms.containsKey(id))
        }
        var seen = 0
        repeat(60) {
            val d = t.previewDrill() ?: return@repeat
            t.startDrill(d)
            for (p in d.prompts) {
                if (p.atomId !in FoundationsTrainer.LANDMARK_ATOMS) continue
                seen++
                assertEquals(FoundationsTrainer.RENDER_STAFF, p.render)
                // The staff IS the question: no words, and no letter to read off.
                assertEquals("", p.label)
                // A staff position names exactly one pitch, so octave slack is wrong.
                assertFalse(p.matchAnyOctave)
                assertTrue(p.clef == Staff.CLEF_TREBLE || p.clef == Staff.CLEF_BASS)
            }
            t.recordResults(d.prompts.map { hit(it) }, dayIndex = it)
        }
        assertTrue("landmark prompts must actually be served", seen > 0)
    }

    @Test
    fun eachLandmarkIsPinnedToItsOwnPitchAndClef() {
        val defs = FoundationsTrainer.defaultAtoms().associateBy { it.id }
        val rng = kotlin.random.Random(1)
        val c4 = defs.getValue(FoundationsTrainer.ATOM_LANDMARK_C4)
        val g4 = defs.getValue(FoundationsTrainer.ATOM_LANDMARK_G4)
        val f3 = defs.getValue(FoundationsTrainer.ATOM_LANDMARK_F3)
        repeat(20) { rung ->
            assertEquals(60, c4.promptNote(rng, rung % 3))
            assertEquals(67, g4.promptNote(rng, rung % 3))
            assertEquals(53, f3.promptNote(rng, rung % 3))
        }
        // Middle C is the shared ledger line, so it must arrive from either side.
        assertEquals(Staff.CLEF_EITHER, c4.clef)
        assertEquals(Staff.CLEF_TREBLE, g4.clef)
        assertEquals(Staff.CLEF_BASS, f3.clef)
        // The landmark clefs point at exactly the landmark pitches.
        assertEquals(
            Staff.clefAnchorHalfSpaces(Staff.CLEF_TREBLE),
            Staff.halfSpaces(67, Staff.CLEF_TREBLE),
        )
        assertEquals(
            Staff.clefAnchorHalfSpaces(Staff.CLEF_BASS),
            Staff.halfSpaces(53, Staff.CLEF_BASS),
        )
    }

    @Test
    fun middleCPromptsArriveInBothClefsOverTime() {
        // Drill only middle C, so every prompt exercises the clef choice.
        val onlyC4 = FoundationsTrainer.defaultAtoms()
            .filter { it.id == FoundationsTrainer.ATOM_LANDMARK_C4 }
        val seen = HashSet<Int>()
        val t = FoundationsTrainer(atomDefs = onlyC4)
        repeat(20) {
            val d = t.previewDrill() ?: return@repeat
            d.prompts.forEach { seen.add(it.clef) }
            t.startDrill(d)
        }
        assertEquals("middle C must be shown from both sides", 2, seen.size)
        assertTrue(seen.contains(Staff.CLEF_TREBLE) && seen.contains(Staff.CLEF_BASS))
    }

    @Test
    fun aFixedPitchAtomCanStillReachMastery() {
        // The beyond-home-octave requirement is evidence that the LETTER
        // transfers across octaves. A landmark has one pitch by definition, so
        // demanding it would make these atoms permanently unmasterable.
        val t = FoundationsTrainer(atomDefs = FoundationsTrainer.defaultAtoms())
        val id = FoundationsTrainer.ATOM_LANDMARK_G4
        val st = t.atoms.getValue(id)
        assertEquals(0, st.maxRung)
        val p = FoundationsTrainer.Prompt(id, 67, "", false, FoundationsTrainer.RENDER_STAFF)
        for (day in 1..3) {
            t.startSitting(day)
            t.recordResults(List(3) { hit(p) }, dayIndex = day)
        }
        assertEquals(0, st.rung)
        assertEquals(0, st.hitsBeyondHomeOctave())
        assertTrue("a pinned-pitch atom must be masterable", st.mastered())
    }

    @Test
    fun landmarkAtomsNeverClimbTheOctaveLadder() {
        val t = FoundationsTrainer(atomDefs = FoundationsTrainer.defaultAtoms())
        val id = FoundationsTrainer.ATOM_LANDMARK_F3
        val p = FoundationsTrainer.Prompt(id, 53, "", false, FoundationsTrainer.RENDER_STAFF)
        repeat(10) { day -> t.recordResults(List(4) { hit(p) }, dayIndex = day) }
        assertEquals("a landmark has one pitch; there is no ladder", 0, t.atoms.getValue(id).rung)
    }

    @Test
    fun letterAtomsKeepTheirLadderAfterTheStaffAtomsLand() {
        val t = FoundationsTrainer(atomDefs = FoundationsTrainer.defaultAtoms())
        assertEquals(t.config.maxRung, t.atoms.getValue("find-C").maxRung)
        assertEquals(t.config.maxRung, t.atoms.getValue(FoundationsTrainer.ATOM_OCTAVES).maxRung)
    }

    @Test
    fun aSavedBlobCannotResurrectAnOldLadderShape() {
        val t = FoundationsTrainer(atomDefs = FoundationsTrainer.defaultAtoms())
        val blob = t.serialize()
        val restored = FoundationsTrainer(blob, atomDefs = FoundationsTrainer.defaultAtoms())
        assertEquals(0, restored.atoms.getValue(FoundationsTrainer.ATOM_LANDMARK_C4).maxRung)
        assertEquals(t.config.maxRung, restored.atoms.getValue("find-G").maxRung)
    }

    @Test
    fun sittingOneIsNotationFromTheFirstPrompt() {
        // SPEC §4a-F: the terminal capability is reading, so it must not arrive
        // at 87.5% of the way through the module. A brand new learner's very
        // first drill is staff prompts and nothing else.
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        assertTrue(
            "the first drill must FOCUS on notation",
            FoundationsTrainer.LANDMARK_ATOMS.contains(d.focusAtom),
        )
        val notated = d.prompts.count { it.render == FoundationsTrainer.RENDER_STAFF }
        assertTrue(
            "notation must dominate the first drill, saw $notated/${d.prompts.size}",
            notated > d.prompts.size / 2,
        )
    }

    @Test
    fun aNeverSeenSymbolIsDemonstratedOnceThenTested() {
        // The onboarding: §2 forbids theory prerequisites, walls of text and
        // menus before playing, so a new symbol is taught by showing the answer
        // once. It is scored as revealed, so it earns nothing.
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        val focus = d.focusAtom
        val focusPrompts = d.prompts.filter { it.atomId == focus }
        assertTrue(focusPrompts.size > 1)
        assertTrue("the first meeting must demonstrate", focusPrompts.first().demonstrate)
        assertTrue(
            "only the first one demonstrates",
            focusPrompts.drop(1).none { it.demonstrate },
        )
    }

    @Test
    fun aSeenSymbolIsNeverDemonstratedAgain() {
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        t.recordResults(d.prompts.map { hit(it) }, dayIndex = 0)
        for (day in 1..6) {
            t.startSitting(day)
            val next = t.previewDrill() ?: continue
            next.prompts.forEach { p ->
                if (t.atoms.getValue(p.atomId).promptsSeen > 0) {
                    assertFalse("${p.atomId} re-demonstrated", p.demonstrate)
                }
            }
            t.startDrill(next)
            t.recordResults(next.prompts.map { hit(it) }, dayIndex = day)
        }
    }

    @Test
    fun demonstrationsCannotBankAHit() {
        // Belt and braces on the scoring rule: a revealed prompt is not unaided,
        // so a drill made entirely of demonstrations banks nothing.
        val t = FoundationsTrainer()
        val d = t.previewDrill()!!
        t.startDrill(d)
        t.recordResults(
            d.prompts.map {
                FoundationsTrainer.PromptResult(
                    it.atomId, unaided = false, revealed = true,
                    latencyMs = 1200, expectedNote = it.midiNote, wrongPresses = emptyList(),
                )
            },
            dayIndex = 0,
        )
        assertEquals(0, t.atoms.getValue(d.focusAtom).totalHits())
        assertEquals(0, t.atoms.getValue(d.focusAtom).daysCredited)
    }

    // ------------------------------------------------ SPEC §4a-F item F2: fade

    @Test
    fun scaffoldStartsFullAndReachesZeroOnUnaidedSuccess() {
        val t = FoundationsTrainer()
        val id = FoundationsTrainer.ATOM_LANDMARK_C4
        val st = t.atoms.getValue(id)
        assertEquals(1f, st.scaffoldAlpha, 1e-6f)
        val p = FoundationsTrainer.Prompt(id, 60, "", false, FoundationsTrainer.RENDER_STAFF)
        repeat(3) { t.recordResults(listOf(hit(p)), dayIndex = it) }
        assertEquals("three clean reps must retire the scaffold", 0f, st.scaffoldAlpha, 1e-6f)
    }

    @Test
    fun failingNeverHandsHelpBack() {
        // A scaffold that returns on failure rewards getting things wrong, and
        // the fade would never terminate.
        val t = FoundationsTrainer()
        val id = FoundationsTrainer.ATOM_LANDMARK_G4
        val st = t.atoms.getValue(id)
        val p = FoundationsTrainer.Prompt(id, 67, "", false, FoundationsTrainer.RENDER_STAFF)
        t.recordResults(listOf(hit(p)), dayIndex = 0)
        val after = st.scaffoldAlpha
        assertTrue(after < 1f)
        repeat(5) { t.recordResults(listOf(missWith(p, listOf(65))), dayIndex = 1) }
        assertEquals("help must never come back", after, st.scaffoldAlpha, 1e-6f)
    }

    @Test
    fun masteryCannotBeClaimedWhileHelpIsStillOnScreen() {
        // Condition 4 of the definition of done.
        val t = FoundationsTrainer()
        val id = FoundationsTrainer.ATOM_LANDMARK_F3
        val st = t.atoms.getValue(id)
        // Force everything EXCEPT the scaffold.
        repeat(t.config.sessionCriterionHits) { st.hitsAtRung[0]++ }
        st.daysCredited = t.config.criterionDays
        st.scaffoldAlpha = 0.2f
        assertTrue(st.atCriterion())
        assertFalse("help still showing — not mastered", st.mastered())
        st.scaffoldAlpha = 0f
        assertTrue(st.mastered())
    }

    @Test
    fun onlyAFullyScaffoldedPromptDemonstrates() {
        val t = FoundationsTrainer()
        val first = t.previewDrill()!!
        assertTrue(first.prompts.any { it.demonstrate })
        // Fade every atom, then no prompt may show its answer outright.
        t.atoms.values.forEach { it.scaffoldAlpha = 0f }
        val later = t.previewDrill()!!
        assertTrue("faded atoms must not demonstrate", later.prompts.none { it.demonstrate })
        assertTrue(later.prompts.all { it.scaffoldAlpha == 0f })
    }

    @Test
    fun scaffoldSurvivesSerializationAndOldBlobsDegradeSafely() {
        val t = FoundationsTrainer()
        val id = FoundationsTrainer.ATOM_LANDMARK_C4
        val p = FoundationsTrainer.Prompt(id, 60, "", false, FoundationsTrainer.RENDER_STAFF)
        t.recordResults(listOf(hit(p)), dayIndex = 0)
        val alpha = t.atoms.getValue(id).scaffoldAlpha
        val restored = FoundationsTrainer(t.serialize())
        assertEquals(alpha, restored.atoms.getValue(id).scaffoldAlpha, 1e-6f)

        // A blob written before the field existed: an atom with history has
        // already earned its fade, an untouched one has not.
        // Exactly the 14 fields the format had before scaffoldAlpha was added.
        val legacy = "$id=3,0,0,0,0,0,0,0:0:1:0:0:1:0:4:3:0.0:0.0:0.0:1:4"
        val old = FoundationsTrainer(legacy)
        assertEquals(0f, old.atoms.getValue(id).scaffoldAlpha, 1e-6f)
    }

    // -------------------------------------------- SPEC §4a-F item F2: the span

    @Test
    fun theSpanAtomCoversEveryPitchTheColdReadUses() {
        val span = FoundationsTrainer.SPAN_PITCHES.toSet()
        for (e in com.fingerly.core.notation.ExcerptBank.all) {
            for (n in e.notes) {
                assertTrue(
                    "${e.id}: ${n.midi} is read but never trained",
                    span.contains(n.midi),
                )
            }
        }
    }

    @Test
    fun spanPromptsUseTheClefRealNotationWouldUse() {
        val t = FoundationsTrainer()
        val defs = FoundationsTrainer.defaultAtoms()
            .filter { it.id == FoundationsTrainer.ATOM_SPAN }
        val only = FoundationsTrainer(atomDefs = defs)
        var sawBass = false
        var sawTreble = false
        repeat(30) {
            val d = only.previewDrill() ?: return@repeat
            for (p in d.prompts) {
                assertEquals(FoundationsTrainer.RENDER_STAFF, p.render)
                if (p.midiNote < 60) {
                    assertEquals("${p.midiNote} must read in bass", Staff.CLEF_BASS, p.clef)
                    sawBass = true
                } else if (p.midiNote > 60) {
                    assertEquals("${p.midiNote} must read in treble", Staff.CLEF_TREBLE, p.clef)
                    sawTreble = true
                }
            }
            only.startDrill(d)
        }
        assertTrue("the span must exercise both clefs", sawBass && sawTreble)
        assertTrue(t.atoms.containsKey(FoundationsTrainer.ATOM_SPAN))
    }

    // ---------------------------------------------- SPEC §4a-F item F5: hands

    @Test
    fun handsTogetherPairsAreAlwaysSeparablyFarApart() {
        val rng = kotlin.random.Random(7)
        for (right in FoundationsTrainer.HANDS_RIGHT_POOL) {
            repeat(20) {
                val left = FoundationsTrainer.pairFor(right, rng)
                assertTrue("no legal partner for $right", left >= 0)
                assertTrue(
                    "$right over $left is only ${right - left} semitones",
                    right - left >=
                        com.fingerly.core.notation.ExcerptBank.MIN_HAND_SEPARATION_SEMITONES,
                )
            }
        }
    }

    @Test
    fun oneHandCannotSatisfyAHandsTogetherPrompt() {
        // Both notes are due at the same instant, so wait mode holds until both
        // land. If only one chart note were emitted the atom would certify a
        // skill MIDI cannot observe — the fingering-atom mistake.
        val p = FoundationsTrainer.Prompt(
            FoundationsTrainer.ATOM_HANDS, 67, "", false,
            FoundationsTrainer.RENDER_STAFF, Staff.CLEF_EITHER, secondNote = 48,
        )
        assertTrue(p.handsTogether)
        val drill = FoundationsTrainer.Drill("x", "x", null, listOf(p))
        val score = FoundationsTrainer.toScore(drill)
        assertEquals(2, score.notes.size)
        assertEquals(score.notes[0].startSeconds, score.notes[1].startSeconds, 1e-9)
        assertEquals(48, score.notes[0].midiNote)
        assertEquals(67, score.notes[1].midiNote)
    }

    @Test
    fun promptIndicesStillMapToChartNotesWhenPromptsEmitPairs() {
        // Every per-prompt measurement is keyed by CHART index, so the moment a
        // prompt can emit two notes the two stop being interchangeable.
        val single = FoundationsTrainer.Prompt("a", 60, "", false)
        val pair = FoundationsTrainer.Prompt(
            FoundationsTrainer.ATOM_HANDS, 67, "", false,
            FoundationsTrainer.RENDER_STAFF, Staff.CLEF_EITHER, secondNote = 48,
        )
        val drill = FoundationsTrainer.Drill("x", "x", null, listOf(single, pair, single))
        val starts = FoundationsTrainer.promptChartStarts(drill)
        assertEquals(listOf(0, 1, 3), starts.toList())
        assertEquals(4, FoundationsTrainer.toScore(drill).notes.size)
        assertEquals(1, FoundationsTrainer.chartNotesPerPrompt(single))
        assertEquals(2, FoundationsTrainer.chartNotesPerPrompt(pair))
    }

    @Test
    fun everyServedHandsPromptIsAGenuinePair() {
        val defs = FoundationsTrainer.defaultAtoms()
            .filter { it.id == FoundationsTrainer.ATOM_HANDS }
        val t = FoundationsTrainer(atomDefs = defs)
        repeat(15) {
            val d = t.previewDrill() ?: return@repeat
            for (p in d.prompts) {
                assertTrue("hands prompt served as a single note", p.handsTogether)
                assertTrue(
                    p.midiNote - p.secondNote >=
                        com.fingerly.core.notation.ExcerptBank.MIN_HAND_SEPARATION_SEMITONES,
                )
            }
            t.startDrill(d)
        }
    }
}
