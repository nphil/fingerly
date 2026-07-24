package com.fingerly.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FsrsTest {

    @Test
    fun measuredAccuracyMapsToGrades() {
        assertEquals(Fsrs.GRADE_AGAIN, Fsrs.gradeOf(40f))
        assertEquals(Fsrs.GRADE_HARD, Fsrs.gradeOf(75f))
        assertEquals(Fsrs.GRADE_GOOD, Fsrs.gradeOf(90f))
        assertEquals(Fsrs.GRADE_EASY, Fsrs.gradeOf(98f))
    }

    @Test
    fun betterFirstGradesGiveLongerIntervals() {
        val again = Fsrs.intervalDays(Fsrs.initial(Fsrs.GRADE_AGAIN))
        val good = Fsrs.intervalDays(Fsrs.initial(Fsrs.GRADE_GOOD))
        val easy = Fsrs.intervalDays(Fsrs.initial(Fsrs.GRADE_EASY))
        assertTrue(again < good)
        assertTrue(good < easy)
    }

    @Test
    fun successGrowsStabilityFailureShrinksIt() {
        var card = Fsrs.initial(Fsrs.GRADE_GOOD)
        val s0 = card.stability
        card = Fsrs.review(card, Fsrs.GRADE_GOOD, elapsedDays = Fsrs.intervalDays(card))
        assertTrue(card.stability > s0)
        val grown = card.stability
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, elapsedDays = 1.0)
        assertTrue(card.stability < grown)
    }

    @Test
    fun repeatedSuccessSpacesReviewsOut() {
        var card = Fsrs.initial(Fsrs.GRADE_GOOD)
        var lastInterval = 0.0
        repeat(5) {
            val interval = Fsrs.intervalDays(card)
            assertTrue(interval > lastInterval)
            lastInterval = interval
            card = Fsrs.review(card, Fsrs.GRADE_GOOD, elapsedDays = interval)
        }
        assertTrue(lastInterval > 5.0) // spaced out to multi-day after 5 cleans
    }

    @Test
    fun failuresRaiseDifficulty() {
        var card = Fsrs.initial(Fsrs.GRADE_GOOD)
        val d0 = card.difficulty
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, 1.0)
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, 1.0)
        assertTrue(card.difficulty > d0)
    }

    @Test
    fun retrievabilityDecaysWithTime() {
        val r0 = Fsrs.retrievability(stability = 3.0, elapsedDays = 0.0)
        val r3 = Fsrs.retrievability(stability = 3.0, elapsedDays = 3.0)
        val r30 = Fsrs.retrievability(stability = 3.0, elapsedDays = 30.0)
        assertEquals(1.0, r0, 1e-9)
        assertTrue(r3 in 0.85..0.95) // ~90% at t=S with the 9x law
        assertTrue(r30 < r3)
    }
}
