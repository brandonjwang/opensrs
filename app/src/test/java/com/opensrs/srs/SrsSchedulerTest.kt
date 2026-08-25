package com.opensrs.srs

import com.opensrs.data.db.CardState
import com.opensrs.data.db.FlashcardStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the SM-2 scheduler: state transitions, ease bounds,
 * interval math, and lapse handling.
 */
class SrsSchedulerTest {

    private val scheduler = SrsScheduler()
    private val now = 1_700_000_000_000L

    private fun newCard() = FlashcardStateEntity(wordId = 1L)

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val MINUTE_MS = 60_000L
    }

    // -- New cards ---------------------------------------------------------------

    @Test
    fun `new card Again enters first learning step`() {
        val r = scheduler.review(newCard(), SrsScheduler.RATING_AGAIN, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(0, r.repetitions)
        assertEquals(2.5f, r.easeFactor, 1e-6f)
        assertEquals(now + MINUTE_MS, r.dueAt)
        assertEquals(now, r.updatedAt)
        assertEquals(1, r.totalReviews)
        assertEquals(0, r.lapses)
    }

    @Test
    fun `new card Good goes to middle learning step`() {
        val r = scheduler.review(newCard(), SrsScheduler.RATING_GOOD, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(10 * MINUTE_MS, r.dueAt - now) // steps are 1m,10m -> mid = index1 = 10m
        assertEquals(0, r.repetitions)
    }

    @Test
    fun `new card Easy graduates at easy interval with ease bonus`() {
        val r = scheduler.review(newCard(), SrsScheduler.RATING_EASY, now)
        assertEquals(CardState.GRADUATED, r.state)
        assertEquals(4f, r.intervalDays, 1e-4f)
        assertEquals(2.65f, r.easeFactor, 1e-4f)
        assertEquals(1, r.repetitions)
        assertEquals(now + (4f * DAY_MS).toLong(), r.dueAt)
    }

    @Test
    fun `new card Hard drops ease and uses middle step`() {
        val r = scheduler.review(newCard(), SrsScheduler.RATING_HARD, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(2.35f, r.easeFactor, 1e-4f)
    }

    // -- Learning steps ------------------------------------------------------------

    private fun learningCard(stepReps: Int, ease: Float = 2.5f) = FlashcardStateEntity(
        wordId = 1L,
        state = CardState.LEARNING,
        easeFactor = ease,
        intervalDays = 10f / (24 * 60),
        repetitions = stepReps,
        dueAt = now,
        updatedAt = now,
        totalReviews = 1,
    )

    @Test
    fun `learning Good on last step graduates at one day`() {
        val r = scheduler.review(learningCard(stepReps = 1), SrsScheduler.RATING_GOOD, now)
        assertEquals(CardState.GRADUATED, r.state)
        assertEquals(1f, r.intervalDays, 1e-4f)
        assertEquals(now + DAY_MS, r.dueAt)
    }

    @Test
    fun `learning Good mid-steps advances repetition count`() {
        val r = scheduler.review(learningCard(stepReps = 0), SrsScheduler.RATING_GOOD, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(1, r.repetitions)
        assertEquals(10 * MINUTE_MS, r.dueAt - now)
    }

    @Test
    fun `learning Again resets to first step without lapsing`() {
        val c = learningCard(stepReps = 1, ease = 2.3f)
        val r = scheduler.review(c, SrsScheduler.RATING_AGAIN, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(0, r.repetitions)
        assertEquals(0, r.lapses)
        assertEquals(now + MINUTE_MS, r.dueAt)
    }

    @Test
    fun `learning Easy graduates at one and a half days minimum`() {
        val r = scheduler.review(learningCard(stepReps = 0), SrsScheduler.RATING_EASY, now)
        assertEquals(CardState.GRADUATED, r.state)
        assertEquals(4f, r.intervalDays, 1e-4f) // max(1*1.5, 4)
    }

    // -- Graduated cards ---------------------------------------------------------

    private fun graduatedCard(
        intervalDays: Float,
        reps: Int = 5,
        ease: Float = 2.5f,
    ) = FlashcardStateEntity(
        wordId = 1L,
        state = CardState.GRADUATED,
        easeFactor = ease,
        intervalDays = intervalDays,
        repetitions = reps,
        dueAt = now,
        updatedAt = now,
        totalReviews = 10,
        lapses = 1,
    )

    @Test
    fun `graduated Good multiplies interval by ease`() {
        val r = scheduler.review(graduatedCard(intervalDays = 10f), SrsScheduler.RATING_GOOD, now)
        assertEquals(25f, r.intervalDays, 1e-3f)
        assertEquals(2.5f, r.easeFactor, 1e-6f)
        assertEquals(6, r.repetitions)
        assertEquals(11, r.totalReviews)
    }

    @Test
    fun `graduated Hard gives small bump and drops ease`() {
        val r = scheduler.review(graduatedCard(intervalDays = 10f), SrsScheduler.RATING_HARD, now)
        assertEquals(12f, r.intervalDays, 1e-3f) // 10 * 1.2
        assertEquals(2.35f, r.easeFactor, 1e-4f)
    }

    @Test
    fun `graduated Easy multiplies extra and raises ease`() {
        val r = scheduler.review(graduatedCard(intervalDays = 10f), SrsScheduler.RATING_EASY, now)
        assertEquals(32.5f, r.intervalDays, 1e-3f) // 10 * 2.5 * 1.3
        assertEquals(2.65f, r.easeFactor, 1e-4f)
    }

    @Test
    fun `graduated Again lapses into learning and floors ease`() {
        val r = scheduler.review(graduatedCard(intervalDays = 21f, ease = 1.35f), SrsScheduler.RATING_AGAIN, now)
        assertEquals(CardState.LEARNING, r.state)
        assertEquals(0, r.repetitions)
        assertEquals(1.3f, r.easeFactor, 1e-4f) // floored at minEase
        assertEquals(2, r.lapses)
        assertEquals(11, r.totalReviews)
    }

    @Test
    fun `ease never drops below floor across repeated lapses`() {
        var c = graduatedCard(intervalDays = 5f, ease = 2.5f)
        repeat(10) {
            c = scheduler.review(c.copy(dueAt = now), SrsScheduler.RATING_AGAIN, now)
        }
        assertTrue(c.easeFactor >= 1.3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid rating rejected`() {
        scheduler.review(newCard(), rating = 7, nowMs = now)
    }
}
