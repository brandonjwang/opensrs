package com.opensrs.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface WordDao {

    /**
     * The review queue ordering: spoken-utility first.
     *
     * For a Mandarin-primary learner, words ranked in SUBTLEX-CH come before words
     * only attested in Cantonese corpora; within equally-ranked groups the id keeps
     * ordering deterministic (the generator assigns ids by ascending rank).
     */
    @Query(
        """
        SELECT * FROM words
        ORDER BY
            CASE WHEN :preferCantonese = 1
                 THEN COALESCE(cantoneseRank, 1000000 + mandarinRank, 2000000)
                 ELSE COALESCE(mandarinRank, 1000000 + cantoneseRank, 2000000)
            END ASC,
            id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun pageBySpokenFrequency(limit: Int, offset: Long, preferCantonese: Boolean): List<WordEntity>

    /** Deterministic new-card selection window for the daily queue. */
    @Query(
        """
        SELECT * FROM words
        ORDER BY
            CASE WHEN :preferCantonese = 1
                 THEN COALESCE(cantoneseRank, 1000000 + mandarinRank, 2000000)
                 ELSE COALESCE(mandarinRank, 1000000 + cantoneseRank, 2000000)
            END ASC,
            id ASC
        LIMIT :window
        """
    )
    suspend fun topBySpokenFrequency(window: Int, preferCantonese: Boolean): List<WordEntity>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun byId(id: Long): WordEntity?

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM words
        WHERE simplified LIKE '%' || :query || '%'
           OR traditional LIKE '%' || :query || '%'
           OR english LIKE '%' || :query || '%'
           OR pinyin LIKE '%' || :query || '%'
           OR jyutping LIKE '%' || :query || '%'
        ORDER BY COALESCE(mandarinRank, cantoneseRank, 999999) ASC
        LIMIT 50
        """
    )
    suspend fun search(query: String): List<WordEntity>
}
