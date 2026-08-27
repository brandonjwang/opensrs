package com.opensrs.data.db

import androidx.room.Dao
import androidx.room.Upsert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashcardDao {

    @Query("SELECT * FROM cards WHERE wordId = :wordId")
    suspend fun byWord(wordId: Long): FlashcardStateEntity?

    /**
     * All non-suspended states for the given word ids, regardless of due date.
     * Used to classify candidates as NEW vs. already-studied when assembling a
     * session — a card answered "Easy" has a future due date and must NOT be
     * mistaken for NEW just because it is not currently due.
     */
    @Query("SELECT * FROM cards WHERE wordId IN (:wordIds) AND state != 'SUSPENDED'")
    suspend fun statesFor(wordIds: List<Long>): List<FlashcardStateEntity>

    @Query(
        """
        SELECT * FROM cards
        WHERE state IN ('LEARNING', 'GRADUATED') AND dueAt <= :now
        ORDER BY dueAt ASC
        LIMIT :limit
        """
    )
    suspend fun dueKnown(limit: Int, now: Long): List<FlashcardStateEntity>

    /** Learn-ahead: soonest-due LEARNING cards within [now, horizon]. */
    @Query(
        """
        SELECT * FROM cards
        WHERE state = 'LEARNING' AND dueAt > :now AND dueAt <= :horizon
        ORDER BY dueAt ASC
        LIMIT :limit
        """
    )
    suspend fun nextLearning(limit: Int, now: Long, horizon: Long): List<FlashcardStateEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE state = 'NEW'")
    fun newCount(): Flow<Int>

    /** Distinct due-day buckets for the next 7 days (forecast chart). */
    @Query(
        """
        SELECT COUNT(*) FROM cards
        WHERE state IN ('LEARNING', 'GRADUATED') AND dueAt > :dayStart AND dueAt <= :dayEnd
        """
    )
    suspend fun dueCountBetween(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE state = 'GRADUATED'")
    suspend fun graduatedCount(): Int

    @Query("SELECT COALESCE(SUM(totalReviews), 0) FROM cards")
    suspend fun totalReviews(): Int

    @Query("SELECT COALESCE(SUM(lapses), 0) FROM cards")
    suspend fun totalLapses(): Int

    /** Young vs mature split: interval >= 21 days counts as mature. */
    @Query("SELECT COUNT(*) FROM cards WHERE state = 'GRADUATED' AND intervalDays >= 21")
    suspend fun matureCount(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE state IN ('LEARNING', 'GRADUATED') AND dueAt <= :now")
    fun dueCount(now: Long): Flow<Int>

    @Query("SELECT * FROM cards")
    suspend fun all(): List<FlashcardStateEntity>

    /** Word ids the user permanently marked as known; excluded from every queue. */
    @Query("SELECT wordId FROM cards WHERE state = 'SUSPENDED'")
    suspend fun skippedIds(): List<Long>

    @Query("SELECT MAX(updatedAt) FROM cards")
    suspend fun maxUpdatedAt(): Long?

    @Upsert
    suspend fun upsert(card: FlashcardStateEntity)

    @Upsert
    suspend fun upsertAll(cards: List<FlashcardStateEntity>)

    @Query("DELETE FROM cards WHERE wordId NOT IN (:keepWordIds)")
    suspend fun deleteNotIn(keepWordIds: List<Long>)

    @Query("DELETE FROM cards")
    suspend fun clear()
}
