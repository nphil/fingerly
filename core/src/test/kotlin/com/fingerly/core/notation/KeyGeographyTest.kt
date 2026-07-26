package com.fingerly.core.notation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run script states these facts to a learner who cannot yet check
 * them. Every one is asserted here, because telling a beginner something false
 * about the instrument in front of them is the worst failure available.
 */
class KeyGeographyTest {

    @Test
    fun theBoardIs88KeysAnd52WhiteOnes() {
        assertEquals(88, KeyGeography.HIGHEST - KeyGeography.LOWEST + 1)
        assertEquals(52, KeyGeography.whiteKeyCount())
    }

    @Test
    fun thereAreExactlySevenCompleteGroupsOfTwo() {
        // The script says "seven groups of two" and "the middle one has three
        // groups on its left and three on its right". Both must be literally true.
        assertEquals(7, KeyGeography.completePairGroups())
        assertEquals(7, KeyGeography.completeTripleGroups())
    }

    @Test
    fun middleCSitsAgainstTheMiddleGroupWithThreeEitherSide() {
        val idx = KeyGeography.middleCPairGroupIndex()
        val total = KeyGeography.completePairGroups()
        assertEquals("three groups to the left", 3, idx)
        assertEquals("three groups to the right", 3, total - idx - 1)
    }

    @Test
    fun middleCIsTheTwentyFourthWhiteKeyAndLeftOfCentre() {
        assertEquals(23, KeyGeography.whiteIndex(KeyGeography.MIDDLE_C)) // 0-based → 24th
        // 23 white keys to its left, 28 to its right: it must never be drawn
        // at the midpoint of the instrument.
        val left = KeyGeography.whiteIndex(KeyGeography.MIDDLE_C)
        val right = KeyGeography.whiteKeyCount() - left - 1
        assertEquals(23, left)
        assertEquals(28, right)
        assertTrue("middle C sits left of centre", left < right)
    }

    @Test
    fun thereAreEightCsAndTheRuleIsStatedInTheTrueDirection() {
        assertEquals(8, KeyGeography.allCs().size)
        // Every group of two has a C immediately on its left…
        for (m in KeyGeography.LOWEST..KeyGeography.HIGHEST) {
            if (KeyGeography.inPairGroup(m) && m % 12 == 1) {
                assertEquals("the key left of a pair must be a C", 0, (m - 1) % 12)
            }
        }
        // …but the top C has no pair above it, which is why the rule is never
        // stated as "every C has a group of two on its right".
        assertEquals(108, KeyGeography.allCs().last())
        assertTrue(109 > KeyGeography.HIGHEST)
    }

    @Test
    fun theLoneBlackKeyBelongsToNoGroup() {
        // The script points at it and says "ignore this one", so it must not
        // satisfy either group rule — including the pitch-class shortcut that
        // would wrongly accept A#0 as a member of a group of three.
        assertTrue(KeyGeography.isOrphanBlackKey(22))
        assertTrue(KeyGeography.isBlack(22))
        // It shares a pitch class with the top key of a group of three, which is
        // exactly why a pitch-class test would wrongly accept it. The step must
        // check membership of a COMPLETE group instead.
        assertEquals(10, 22 % 12)
        val members = (KeyGeography.LOWEST..KeyGeography.HIGHEST).filter {
            KeyGeography.inTripleGroup(it) &&
                KeyGeography.tripleGroupIndex(it) == KeyGeography.tripleGroupIndex(22)
        }
        assertEquals("A#0 is alone in its group", 1, members.size)
    }

    @Test
    fun wrongPressesAreReportedInWhiteKeysAndTheSignIsRight() {
        // "3 white keys left" must mean exactly that.
        assertEquals(-3, KeyGeography.whiteKeyDelta(65, 60)) // F4 → C4
        assertEquals(2, KeyGeography.whiteKeyDelta(60, 64)) // C4 → E4
        assertEquals(0, KeyGeography.whiteKeyDelta(60, 60))
        // A black key resolves to the white key below it, so the count is never
        // ambiguous for a learner who is counting white keys.
        assertEquals(1, KeyGeography.whiteKeyDelta(61, 62)) // C#4 → D4
        assertEquals(0, KeyGeography.whiteKeyDelta(61, 60))
    }

    @Test
    fun cPositionIsFiveConsecutiveWhiteKeysFromMiddleC() {
        val pos = KeyGeography.C_POSITION
        assertEquals(5, pos.size)
        assertEquals(KeyGeography.MIDDLE_C, pos.first())
        for (i in pos.indices) {
            assertFalse("finger ${i + 1} must be on a white key", KeyGeography.isBlack(pos[i]))
            assertEquals("one white key apart", i, KeyGeography.whiteKeyDelta(pos[0], pos[i]))
        }
    }
}
