package com.opensrs.data.db

/**
 * In-memory search index over the whole dictionary (~11k entries).
 *
 * Why not SQL LIKE: the stored pinyin/jyutping carry diacritics and tone
 * digits ("liáo tiān", "king1 wai6*5"). A learner types plain ASCII ("liao",
 * "king wai"), and SQLite LIKE has no accent-insensitive collation — so a raw
 * query silently misses most romanization matches. This index stores a
 * normalized form of every searchable field and matches against that:
 *
 *  - lowercase, whitespace collapsed, trimmed
 *  - NFD decomposition strips pinyin tone marks (nǐ -> ni, lüe -> lue)
 *  - a second variant drops jyutping tone digits and separator symbols
 *    ("ngo5" -> "ngo", "wai6*5" -> "wai") so digitless queries match
 *
 * Chinese fields are matched verbatim; english/pinyin/jyutping via their
 * normalized forms. Ranking keeps the spoken-frequency order used everywhere
 * else in the app.
 */
class WordSearchIndex private constructor(
    private val words: List<WordEntity>,
    private val normalized: List<RowKeys>,
) {
    private class RowKeys(
        val hanzi: String,       // simplified + traditional, lowercased
        val latin: String,       // english + pinyin + jyutping, normalized
        val latinDigitless: String,
        val latinNoSpace: String, // digitless AND space-free: matches "nihao" against "nǐ hǎo"
    )

    val size: Int get() = words.size

    /** Highest-spoken-frequency entries, mirroring the DAO's default listing. */
    fun top(limit: Int): List<WordEntity> = words.take(limit)

    fun search(query: String, limit: Int = 50): List<WordEntity> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qDigitless = stripToneDigits(q)
        val qNoSpace = qDigitless.replace(" ", "")
        // substring containment on normalized keys; frequency order preserved by construction
        return words.filterIndexed { i, _ ->
            val k = normalized[i]
            k.hanzi.contains(q) || k.latin.contains(q) ||
                (qDigitless != q && k.latinDigitless.contains(qDigitless)) ||
                (qNoSpace.length >= 2 && k.latinNoSpace.contains(qNoSpace))
        }.take(limit)
    }
    companion object {
        suspend fun build(wordDao: WordDao): WordSearchIndex {
            val sorted = wordDao.allForSearch().sortedWith(
                compareBy(
                    { it.mandarinRank ?: (it.cantoneseRank?.let { r -> 1_000_000 + r } ?: 2_000_000) },
                    { it.id },
                ),
            )
            val rows = sorted.map { w ->
                val latin = normalize("${w.english} ${w.pinyin} ${w.jyutping}")
                val digitless = stripToneDigits(latin)
                RowKeys(
                    hanzi = "${w.simplified} ${w.traditional}".lowercase(),
                    latin = latin,
                    latinDigitless = digitless,
                    latinNoSpace = digitless.replace(" ", ""),
                )
            }
            return WordSearchIndex(sorted, rows)
        }

        /** Lowercase, collapse whitespace, strip diacritics via NFD decomposition. */
        fun normalize(s: String): String {
            val lowered = s.lowercase().trim().replace(Regex("\\s+"), " ")
            if (lowered.all { it.code < 0x80 }) return lowered
            val decomposed = java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{Mn}+"), "")
        }

        /** Remove jyutping tone digits and separator symbols: "wai6*5" -> "wai". */
        fun stripToneDigits(s: String): String =
            s.replace(Regex("[0-9*·]"), "").replace(Regex(" +"), " ").trim()

        fun empty(): WordSearchIndex = WordSearchIndex(emptyList(), emptyList())
    }
}
