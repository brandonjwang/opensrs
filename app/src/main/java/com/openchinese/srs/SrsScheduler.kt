package com.openchinese.srs

import com.openchinese.data.db.CardState
import com.openchinese.data.db.FlashcardStateEntity

/**
 * Offline SM-2 scheduler (Anki-style ratings).
 *
 * Ratings: 0 = Again, 1 = Hard, 2 = Good, 3 = Easy.
 *
 * Behavior:
 *  - Again: lapses the card (repetitions=0, ease −0.20), back to first learning step.
 *  - Hard: ease −0.15; learning cards repeat the current step, graduated cards get
 *    a small interval bump instead of the full ease multiple.
 *  - Good: standard SM-2 progression (learning steps → 1d → 6d → interval × ease).
 *  - Easy: ×1.30 on top of Good, ease +0.15.
 *
 * Pure and deterministic: no clock reads, no I/O. Callers inject `now`.
 */
class SrsScheduler(
    private val config: Config = Config(),
) {

    data class Config(
        val learningStepsMinutes: List<Int> = listOf(1, 10),
        val graduatingIntervalDays: Float = 1f,
        val easyIntervalDays: Float = 4f,
        val startEase: Float = 2.5f,
        val minEase: Float = 1.3f,
    )

    /** @param rating 0=Again 1=Hard 2=Good 3=Easy */
    fun review(card: FlashcardStateEntity, rating: Int, nowMs: Long): FlashcardStateEntity {
        require(rating in 0..3) { "rating must be 0..3, was $rating" }
        return when (card.state) {
            CardState.NEW -> reviewNew(card, rating, nowMs)
            CardState.LEARNING -> reviewLearning(card, rating, nowMs)
            CardState.GRADUATED -> reviewGraduated(card, rating, nowMs)
        }
    }

    // -- New cards ------------------------------------------------------------

    private fun reviewNew(card: FlashcardStateEntity, rating: Int, now: Long): FlashcardStateEntity =
        when (rating) {
            RATING_AGAIN -> toLearning(card, now, minutes = config.learningStepsMinutes.first(), reps = 0)
            RATING_HARD -> toLearning(card, now, minutes = midStep(), reps = 0, easeDelta = HARD_EASE_DELTA)
            RATING_GOOD -> toLearning(card, now, minutes = midStep(), reps = 0)
            else -> graduate(card, now, intervalDays = config.easyIntervalDays, easeDelta = EASY_EASE_DELTA)
        }

    // -- Learning steps -------------------------------------------------------

    private fun reviewLearning(card: FlashcardStateEntity, rating: Int, now: Long): FlashcardStateEntity {
        val stepIndex = card.repetitions.coerceIn(0, config.learningStepsMinutes.lastIndex)
        return when (rating) {
            RATING_AGAIN -> toLearning(card, now, minutes = config.learningStepsMinutes.first(), reps = 0)

            RATING_HARD ->
                // Repeat the current step; ease still drops.
                toLearning(card, now, minutes = config.learningStepsMinutes[stepIndex], reps = card.repetitions, easeDelta = HARD_EASE_DELTA)

            RATING_GOOD -> {
                val nextIdx = stepIndex + 1
                if (nextIdx > config.learningStepsMinutes.lastIndex) {
                    graduate(card, now, intervalDays = config.graduatingIntervalDays)
                } else {
                    toLearning(card, now, minutes = config.learningStepsMinutes[nextIdx], reps = card.repetitions + 1)
                }
            }

            else -> graduate(
                card,
                now,
                intervalDays = maxOf(config.graduatingIntervalDays * 1.5f, config.easyIntervalDays),
                easeDelta = EASY_EASE_DELTA,
            )
        }
    }

    // -- Graduated (review) cards ---------------------------------------------

    private fun reviewGraduated(card: FlashcardStateEntity, rating: Int, now: Long): FlashcardStateEntity {
        val ease = card.easeFactor
        val interval = card.intervalDays
        return when (rating) {
            RATING_AGAIN -> {
                // Lapse: reset progress, drop ease, re-enter learning at step 1.
                FlashcardStateEntity(
                    wordId = card.wordId,
                    state = CardState.LEARNING,
                    easeFactor = (ease - 0.20f).coerceAtLeast(config.minEase),
                    intervalDays = 0f,
                    repetitions = 0,
                    dueAt = now + minutesToMs(config.learningStepsMinutes.first()),
                    updatedAt = now,
                    totalReviews = card.totalReviews + 1,
                    lapses = card.lapses + 1,
                )
            }

            RATING_HARD -> toGraduated(
                card, now,
                interval = maxOf(MIN_GRADUATED_INTERVAL_DAYS, interval * HARD_MULTIPLIER),
                easeDelta = HARD_EASE_DELTA,
                reps = card.repetitions + 1,
            )

            RATING_GOOD -> {
                val next = if (card.repetitions <= 0) config.graduatingIntervalDays else interval * ease
                toGraduated(card, now, interval = next, easeDelta = 0f, reps = card.repetitions + 1)
            }

            else -> {
                val base = if (card.repetitions <= 0) maxOf(config.graduatingIntervalDays, interval) else interval * ease
                toGraduated(card, now, interval = base * EASY_MULTIPLIER, easeDelta = EASY_EASE_DELTA, reps = card.repetitions + 1)
            }
        }
    }

    // -- State constructors -----------------------------------------------------

    private fun toLearning(
        card: FlashcardStateEntity,
        now: Long,
        minutes: Int,
        reps: Int,
        easeDelta: Float = 0f,
    ): FlashcardStateEntity = FlashcardStateEntity(
        wordId = card.wordId,
        state = CardState.LEARNING,
        easeFactor = (card.easeFactor + easeDelta).coerceAtLeast(config.minEase),
        intervalDays = minutes / (24f * 60f),
        repetitions = reps,
        dueAt = now + minutesToMs(minutes),
        updatedAt = now,
        totalReviews = card.totalReviews + 1,
        lapses = card.lapses,
    )

    private fun graduate(
        card: FlashcardStateEntity,
        now: Long,
        intervalDays: Float,
        easeDelta: Float = 0f,
    ): FlashcardStateEntity = FlashcardStateEntity(
        wordId = card.wordId,
        state = CardState.GRADUATED,
        easeFactor = (card.easeFactor + easeDelta).coerceAtLeast(config.minEase),
        intervalDays = intervalDays,
        repetitions = 1,
        dueAt = now + daysToMs(intervalDays),
        updatedAt = now,
        totalReviews = card.totalReviews + 1,
        lapses = card.lapses,
    )

    private fun toGraduated(
        card: FlashcardStateEntity,
        now: Long,
        interval: Float,
        easeDelta: Float,
        reps: Int,
    ): FlashcardStateEntity = FlashcardStateEntity(
        wordId = card.wordId,
        state = CardState.GRADUATED,
        easeFactor = (card.easeFactor + easeDelta).coerceAtLeast(config.minEase),
        intervalDays = interval,
        repetitions = reps,
        dueAt = now + daysToMs(interval),
        updatedAt = now,
        totalReviews = card.totalReviews + 1,
        lapses = card.lapses,
    )

    private fun midStep(): Int =
        config.learningStepsMinutes[config.learningStepsMinutes.size / 2]

    private fun minutesToMs(m: Int): Long = m * 60_000L

    private fun daysToMs(d: Float): Long = (d * 24f * 60f * 60f * 1000f).toLong()

    companion object {
        const val RATING_AGAIN = 0
        const val RATING_HARD = 1
        const val RATING_GOOD = 2
        const val RATING_EASY = 3

        const val HARD_EASE_DELTA = -0.15f
        const val EASY_EASE_DELTA = +0.15f
        const val EASY_MULTIPLIER = 1.3f
        const val HARD_MULTIPLIER = 1.2f
        const val MIN_GRADUATED_INTERVAL_DAYS = 1f
    }
}
