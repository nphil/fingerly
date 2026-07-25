package com.fingerly.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationsTrainerTest {

    private fun perfect(t: FoundationsTrainer, d: FoundationsTrainer.Drill) =
        t.recordResults(
            d.prompts.map {
                FoundationsTrainer.PromptResult(it.atomId, true, 800, it.midiNote, emptyList())
            },
        )

    @Test
    fun drillFocusesWeakestAtomAndMixesStrongMaterial() {
        val t = FoundationsTrainer()
        val d = t.nextDrill()
        assertFalse(d.isTest)
        assertEquals(FoundationsTrainer.DRILL_LENGTH, d.prompts.size)
        // Majority of prompts target the focus atom.
        val focusCount = d.prompts.count { it.atomId == d.focusAtom }
        assertTrue(focusCount >= FoundationsTrainer.DRILL_LENGTH - 3)
        // New atom gets its intro tip.
        assertEquals(FoundationsTrainer.INTRO_TIPS[d.focusAtom], d.tip)
    }

    @Test
    fun masteryNeedsSpeedNotJustAccuracy() {
        val slow = FoundationsTrainer.AtomStats()
        val fast = FoundationsTrainer.AtomStats()
        repeat(8) {
            slow.addPrompt(true, 6000) // correct but 6s per key
            fast.addPrompt(true, 900)
        }
        assertFalse(slow.mastered())
        assertTrue(fast.mastered())
        assertTrue(slow.masteryPercent() < fast.masteryPercent())
    }

    @Test
    fun errorTaxonomyClassifiesOctaveAndNeighbor() {
        val t = FoundationsTrainer()
        t.recordResults(
            listOf(
                FoundationsTrainer.PromptResult("find-C", false, 3000, 60, listOf(72, 48)),
                FoundationsTrainer.PromptResult("find-C", false, 3000, 60, listOf(59, 62)),
                FoundationsTrainer.PromptResult("find-C", false, 3000, 60, listOf(65)),
            ),
        )
        val st = t.atoms.getValue("find-C")
        assertEquals(2, st.octaveErrors)
        assertEquals(2, st.neighborErrors)
        assertEquals(1, st.otherErrors)
    }

    @Test
    fun octaveErrorsSelectOctaveTip() {
        val t = FoundationsTrainer()
        t.recordResults(
            (1..3).map {
                FoundationsTrainer.PromptResult("find-C", false, 2000, 60, listOf(72))
            },
        )
        // Force find-C to be the focus by making everything else stronger.
        FoundationsTrainer.ATOM_IDS.filter { it != "find-C" }.forEach { id ->
            repeat(8) { t.atoms.getValue(id).addPrompt(true, 900) }
        }
        val d = t.nextDrill()
        assertEquals("find-C", d.focusAtom)
        assertTrue(d.tip!!.contains("octave", ignoreCase = true))
    }

    @Test
    fun fullMasteryLeadsToCheckpointAndReport() {
        val t = FoundationsTrainer()
        var guard = 0
        while (!t.allMastered() && guard < 200) {
            val d = t.nextDrill()
            if (!d.isTest) perfect(t, d)
            guard++
        }
        assertTrue(t.allMastered())
        val test = t.nextDrill()
        assertTrue(test.isTest)
        assertTrue(test.prompts.size >= 10)

        val results = test.prompts.map {
            FoundationsTrainer.PromptResult(it.atomId, true, 900, it.midiNote, emptyList())
        }
        assertTrue(t.testPassed(results))
        assertTrue(t.report().contains("mastery"))

        val failed = test.prompts.mapIndexed { i, p ->
            FoundationsTrainer.PromptResult(p.atomId, i % 3 != 0, 900, p.midiNote, emptyList())
        }
        assertFalse(t.testPassed(failed))
    }

    @Test
    fun serializationRoundTrips() {
        val t = FoundationsTrainer()
        perfect(t, t.nextDrill())
        t.recordResults(
            listOf(FoundationsTrainer.PromptResult("find-F", false, 4000, 65, listOf(64))),
        )
        val restored = FoundationsTrainer(t.serialize())
        assertEquals(t.serialize(), restored.serialize())
        assertEquals(
            t.atoms.getValue("find-F").neighborErrors,
            restored.atoms.getValue("find-F").neighborErrors,
        )
    }

    @Test
    fun promptsStayOnRealKeys() {
        val t = FoundationsTrainer()
        repeat(30) {
            val d = t.nextDrill()
            d.prompts.forEach { p ->
                assertTrue(p.midiNote in 21..108)
                if (p.atomId.startsWith("find-")) {
                    val letter = p.atomId.removePrefix("find-")
                    assertEquals(
                        FoundationsTrainer.LETTER_SEMITONE.getValue(letter),
                        p.midiNote % 12,
                    )
                }
            }
            perfect(t, d)
            if (d.isTest) return
        }
    }
}
