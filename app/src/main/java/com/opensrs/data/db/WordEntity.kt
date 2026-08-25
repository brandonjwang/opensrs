package com.opensrs.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A static dictionary entry backed by spoken-corpus frequency ranks.
 *
 * Sourcing:
 *  - Mandarin rank: SUBTLEX-CH word-frequency (spoken-film-subtitle corpus).
 *  - Cantonese rank: HKCAC / utd-cantonese (Hong Kong CanCoral + UTD corpora).
 *
 * Rank semantics: 1 = most frequent spoken form. Lower is more common.
 * [mandarinRank] and [cantoneseRank] are independent because a word can be
 * frequent in one language and rare or absent in the other (e.g. 聊天 vs 傾偈).
 */
@Entity(
    tableName = "words",
    indices = [
        Index("mandarinRank"),
        Index("cantoneseRank"),
        Index("simplified"),
        Index("traditional"),
        Index("hskLevel"),
    ],
)
data class WordEntity(
    @PrimaryKey val id: Long,
    val simplified: String,
    val traditional: String,
    /** Hanyu Pinyin with tone marks, e.g. "liáo tiān". */
    val pinyin: String,
    /** Jyutping romanization, e.g. "king1 wai6*5". */
    val jyutping: String,
    val english: String,
    /** SUBTLEX-CH based spoken rank; null when absent from the corpus. */
    val mandarinRank: Int?,
    /** HKCAC/utd-cantonese based spoken rank; null when absent from the corpus. */
    val cantoneseRank: Int?,
    /** JSON array of example sentences, each {zh, py, jp, en}. */
    val examplesJson: String,
    /** Minimum HSK 3.0 band (1-7) whose inclusive list contains this word. */
    val hskLevel: Int,
)
