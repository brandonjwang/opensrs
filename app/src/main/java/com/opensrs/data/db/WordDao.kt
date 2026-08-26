package com.opensrs.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface WordDao {

    /**
     * The review queue ordering: spoken-utility first, restricted to words from
     * HSK bands [maxLevel] and below (0 = all levels).
     */
    @Query(
        """
        SELECT * FROM words
        WHERE :maxLevel = 0 OR hskLevel <= :maxLevel
        ORDER BY
            CASE WHEN :preferCantonese = 1
                 THEN COALESCE(cantoneseRank, 1000000 + mandarinRank, 2000000)
                 ELSE COALESCE(mandarinRank, 1000000 + cantoneseRank, 2000000)
            END ASC,
            id ASC
        LIMIT :window
        """
    )
    suspend fun topBySpokenFrequency(window: Int, preferCantonese: Boolean, maxLevel: Int): List<WordEntity>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun byId(id: Long): WordEntity?

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM words WHERE hskLevel > 0 AND (:maxLevel = 0 OR hskLevel <= :maxLevel)")
    suspend fun countInLevels(maxLevel: Int): Int

    /** Full dictionary scan source for the in-memory [WordSearchIndex]. */
    @Query("SELECT * FROM words")
    suspend fun allForSearch(): List<WordEntity>

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
