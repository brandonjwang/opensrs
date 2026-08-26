package com.opensrs.sync

import com.opensrs.data.db.CardState
import com.opensrs.data.db.FlashcardStateEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val cards = listOf(
        FlashcardStateEntity(
            wordId = 42L,
            state = CardState.GRADUATED,
            easeFactor = 2.61f,
            intervalDays = 12.5f,
            repetitions = 7,
            dueAt = 1_700_100_000_000L,
            updatedAt = 1_700_050_000_000L,
            totalReviews = 9,
            lapses = 1,
        ),
        FlashcardStateEntity(wordId = 43L),
    )

    @Test
    fun `round trip preserves every field`() {
        val bytes = BackupCodec.encode(cards, deviceName = "Pixel 8", exportedAt = 1_234L)
        val decoded = BackupCodec.decode(bytes)

        assertEquals(BackupCodec.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(1_234L, decoded.exportedAt)
        assertEquals(cards.size, decoded.cards.size)
        assertArrayEquals(cards.toTypedArray(), decoded.cards.toTypedArray())
    }

    @Test
    fun `payload is gzipped`() {
        val bytes = BackupCodec.encode(cards, "dev", 1L)
        // GZIP magic: 0x1F 0x8B
        assertEquals(0x1F, bytes[0].toInt() and 0xFF)
        assertEquals(0x8B, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `compression actually shrinks large histories`() {
        val many = (1..5000).map { i ->
            FlashcardStateEntity(
                wordId = i.toLong(),
                state = CardState.GRADUATED,
                easeFactor = 2.5f,
                intervalDays = 30f,
                repetitions = 12,
                dueAt = 1_700_000_000_000L + i * 1000,
                updatedAt = 1_690_000_000_000L + i * 1000,
                totalReviews = 20,
                lapses = 2,
            )
        }
        val gz = BackupCodec.encode(many, "dev", 1L)
        assertTrue("expected < 60KB, got ${gz.size}", gz.size < 60_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown format versions`() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use {
            it.write("""{"formatVersion":99,"exportedAt":1,"cards":[]}""".toByteArray())
        }
        BackupCodec.decode(bos.toByteArray())
    }

    @Test
    fun `v2 round trip preserves preferences`() {
        val settings = com.opensrs.data.local.UserSettings(
            dailyNewLimit = 25, dailyReviewLimit = 200, hskMaxLevel = 4, hskMinLevel = 2,
            dialectMode = com.opensrs.data.local.DialectMode.CANTONESE,
            romanization = com.opensrs.data.local.RomanizationPref.JYUTPING,
            autoPlayTts = false, showEnglishFirst = true,
        )
        val bytes = BackupCodec.encode(cards, "dev", 99L, settings)
        val decoded = BackupCodec.decode(bytes)
        val p = decoded.prefs!!
        assertEquals(25, p.dailyNewLimit)
        assertEquals(200, p.dailyReviewLimit)
        assertEquals(4, p.hskMaxLevel)
        assertEquals(2, p.hskMinLevel)
        assertEquals(com.opensrs.data.local.DialectMode.CANTONESE, p.dialectMode)
        assertEquals(com.opensrs.data.local.RomanizationPref.JYUTPING, p.romanization)
        assertEquals(false, p.autoPlayTts)
        assertEquals(true, p.showEnglishFirst)
    }

    @Test
    fun `encode without prefs decodes to null prefs`() {
        val bytes = BackupCodec.encode(cards, "dev", 1L)
        assertEquals(null, BackupCodec.decode(bytes).prefs)
    }
}
