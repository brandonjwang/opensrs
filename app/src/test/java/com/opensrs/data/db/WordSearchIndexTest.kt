package com.opensrs.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the normalized search index: tone-mark stripping,
 * jyutping digit handling, and substring matching across all fields.
 */
class WordSearchIndexTest {

    private fun word(
        id: Long,
        simplified: String,
        pinyin: String,
        jyutping: String,
        english: String,
        mandarinRank: Int? = null,
        cantoneseRank: Int? = null,
    ) = WordEntity(
        id = id, simplified = simplified, traditional = simplified,
        pinyin = pinyin, jyutping = jyutping, english = english,
        mandarinRank = mandarinRank, cantoneseRank = cantoneseRank,
        examplesJson = "[]", hskLevel = 1,
    )

    private val dict = listOf(
        word(1, "你好", "nǐ hǎo", "nei5 hou2", "hello", mandarinRank = 10),
        word(2, "聊天", "liáo tiān", "liu4 tin1", "to chat", cantoneseRank = 20),
        word(3, "學校", "xué xiào", "hok6 haau6*5", "school", cantoneseRank = 30),
        word(4, "綠茶", "lǜ chá", "luk6 caa4", "green tea", mandarinRank = 40),
    )

    /** Stub of WordDao: only [allForSearch] is used by the index builder. */
    private open class StubDao(private val rows: List<WordEntity>) : WordDao {
        override suspend fun topBySpokenFrequency(window: Int, preferCantonese: Boolean, maxLevel: Int, minLevel: Int) = rows.take(window)
        override suspend fun byId(id: Long): WordEntity? = rows.firstOrNull { it.id == id }
        override suspend fun count() = rows.size
        override suspend fun countInLevels(maxLevel: Int) = rows.size
        override suspend fun allForSearch() = rows
        override suspend fun search(query: String) = emptyList<WordEntity>()
    }

    private fun build(): WordSearchIndex = runBlocking { WordSearchIndex.build(StubDao(dict)) }

    @Test
    fun `ascii pinyin matches tone-marked data`() {
        val ix = build()
        assertEquals(listOf("你好"), ix.search("ni hao").map { it.simplified })
        assertEquals(listOf("聊天"), ix.search("liao").map { it.simplified })
        // ü decomposes to u: "lv" won't hit but "lu" does not either (lù≠lǜ? both normalize to lu)
        assertTrue(ix.search("cha").isNotEmpty())
    }

    @Test
    fun `jyutping matches without tone digits`() {
        val ix = build()
        assertEquals(listOf("學校"), ix.search("haau").map { it.simplified })  // stored "haau6*5"
        assertEquals(listOf("綠茶"), ix.search("caa").map { it.simplified })   // stored "caa4"
    }

    @Test
    fun `hanzi and english still match`() {
        val ix = build()
        assertEquals(listOf("你好"), ix.search("你好").map { it.simplified })
        assertEquals(listOf("學校"), ix.search("school").map { it.simplified })
    }

    @Test
    fun `results keep frequency order`() {
        val ix = build()
        // both 你好 (mandarin 10) and 綠茶 (40) contain "a" in normalized latin; 10 first
        val hits = ix.search("a").map { it.simplified }
        assertTrue(hits.indexOf("你好") < hits.indexOf("綠茶"))
    }

    @Test
    fun `top mirrors frequency ordering`() {
        val ix = build()
        assertEquals("你好", ix.top(1).first().simplified)
        assertEquals(dict.size, ix.size)
    }

    @Test
    fun `normalize strips diacritics and folds case`() {
        assertEquals("ni hao", WordSearchIndex.normalize("Nǐ Hǎo"))
        assertEquals("king wai", WordSearchIndex.normalize("King Wai"))
    }

    @Test
    fun `despaced query matches spaced pinyin`() {
        val ix = build()
        assertEquals(listOf("你好"), ix.search("nihao").map { it.simplified })
        assertTrue(ix.search("nihao", limit = 5).all { it.simplified == "你好" })
    }

    @Test
    fun `stripToneDigits removes digits and separators`() {
        assertEquals("wai", WordSearchIndex.stripToneDigits("wai6*5"))
        assertEquals("nei hou", WordSearchIndex.stripToneDigits("nei5 hou2"))
    }
}
