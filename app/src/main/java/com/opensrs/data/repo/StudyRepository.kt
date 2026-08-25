package com.opensrs.data.repo

import com.opensrs.data.db.CardState
import com.opensrs.data.db.FlashcardDao
import com.opensrs.data.db.FlashcardStateEntity
import com.opensrs.data.db.WordDao
import com.opensrs.data.db.WordEntity
import com.opensrs.srs.SrsScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One queued item: scheduling state plus its dictionary row (null for NEW cards not yet hydrated). */
data class QueueEntry(
    val wordId: Long,
    val state: FlashcardStateEntity,
)

/**
 * Queue assembly: the two Room databases cannot be joined in SQL (separate files),
 * so the repository stitches frequency-ordered candidates from `words.db` with due
 * state from `srs_state.db`.
 */
class StudyRepository(
    private val wordDao: WordDao,
    private val cardDao: FlashcardDao,
    private val scheduler: SrsScheduler,
) {

    /**
     * Builds today's session: due known cards first (most overdue first), then new
     * cards by spoken-corpus rank, up to [newLimit]/[reviewLimit].
     *
     * The candidate window is a fixed multiple of the daily new limit; words ranked
     * beyond it are introduced on later days, so SQL stays bounded instead of
     * scanning the whole dictionary per session.
     */
    suspend fun buildSession(
        newLimit: Int,
        reviewLimit: Int,
        preferCantonese: Boolean,
        hskMaxLevel: Int = 0,
    ): List<QueueEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val window = newLimit.coerceAtLeast(1) * CANDIDATE_WINDOW_FACTOR + 100

        val dueKnown = cardDao.dueKnown(limit = reviewLimit, now = now)

        val candidates = wordDao.topBySpokenFrequency(window, preferCantonese, hskMaxLevel)
        val statesById = cardDao.dueAmong(candidates.map { it.id }, now)
            .associateBy { it.wordId }

        val newSlots = (newLimit - dueKnown.count { it.state == CardState.NEW }).coerceAtLeast(0)
        val fresh = candidates.asSequence()
            .filter { (statesById[it.id]?.state ?: CardState.NEW) == CardState.NEW }
            .take(newSlots)
            .map { w -> QueueEntry(w.id, statesById[w.id] ?: FlashcardStateEntity(wordId = w.id)) }
            .toList()

        val session = (dueKnown.map { QueueEntry(it.wordId, it) } + fresh)
            .distinctBy { it.wordId }
        if (session.isNotEmpty()) {
            return@withContext session
        }

        // Learn-ahead: nothing due right now, but LEARNING cards come back within
        // minutes — surface the next ones instead of showing an empty session.
        cardDao.nextLearning(LEARN_AHEAD_LIMIT, now, now + LEARN_AHEAD_WINDOW_MS)
            .map { QueueEntry(it.wordId, it) }
    }


    /** Batched hydration of dictionary rows for queue ids, preserving order in the map values' keys. */
    suspend fun hydrate(ids: List<Long>): Map<Long, WordEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            ids.mapNotNull { wordDao.byId(it) }.associateBy { it.id }
        }
    }

    /** Next-interval labels per rating for the answer buttons. */
    fun previewIntervals(card: FlashcardStateEntity): List<String> = scheduler.previewIntervals(card)

    /** Answers a card and returns the new scheduled state. */
    suspend fun answer(card: FlashcardStateEntity, rating: Int): FlashcardStateEntity =
        withContext(Dispatchers.IO) {
            val next = scheduler.review(card.copy(), rating, System.currentTimeMillis())
            cardDao.upsert(next)
            next
        }

    /** Restores a card to its pre-answer state (undo). */
    suspend fun undo(previous: FlashcardStateEntity) = withContext(Dispatchers.IO) {
        cardDao.upsert(previous)
    }

    companion object {
        private const val CANDIDATE_WINDOW_FACTOR = 5

        /** Max cards surfaced by the learn-ahead fallback. */
        private const val LEARN_AHEAD_LIMIT = 10

        /** Look 20 minutes into the future for upcoming learning steps. */
        private const val LEARN_AHEAD_WINDOW_MS = 20 * 60_000L
    }
}
