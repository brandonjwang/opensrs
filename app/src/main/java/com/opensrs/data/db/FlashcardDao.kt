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
     * Due queue joined against the static dictionary for frequency ordering.
     * Room cannot join across database files, so the caller passes a bounded
     * candidate set of word ids ordered by spoken frequency.
     */
    @Query("SELECT * FROM cards WHERE wordId IN (:wordIds) AND dueAt <= :now")
    suspend fun dueAmong(wordIds: List<Long>, now: Long): List<FlashcardStateEntity>

    @Query(
        """
        SELECT * FROM cards
        WHERE state != 'NEW' AND dueAt <= :now
        ORDER BY dueAt ASC
        LIMIT :limit
        """
    )
    suspend fun dueKnown(limit: Int, now: Long): List<FlashcardStateEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE state = 'NEW'")
    fun newCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE state != 'NEW' AND dueAt <= :now")
    fun dueCount(now: Long): Flow<Int>

    @Query("SELECT * FROM cards")
    suspend fun all(): List<FlashcardStateEntity>

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
