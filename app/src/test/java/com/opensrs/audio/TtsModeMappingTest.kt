package com.opensrs.audio

import com.opensrs.data.local.DialectMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins the study-mode -> TTS-locale mapping, including DUAL playback order
 * (Mandarin first, then Cantonese — QUEUE_ADD makes list order audible).
 *
 * Expected values are explicit Locale literals, not TtsManager constants, so a
 * change to the constants themselves is also caught.
 */
class TtsModeMappingTest {

    private val mandarin = Locale("zh", "CN")
    private val cantonese = Locale("zh", "HK")

    @Test
    fun `mandarin mode speaks zh-CN only`() {
        assertEquals(listOf(mandarin), TtsManager.localesFor(DialectMode.MANDARIN))
    }

    @Test
    fun `cantonese mode speaks zh-HK only`() {
        assertEquals(listOf(cantonese), TtsManager.localesFor(DialectMode.CANTONESE))
    }

    @Test
    fun `dual mode speaks mandarin then cantonese`() {
        assertEquals(
            listOf(mandarin, cantonese),
            TtsManager.localesFor(DialectMode.DUAL),
        )
    }
}
