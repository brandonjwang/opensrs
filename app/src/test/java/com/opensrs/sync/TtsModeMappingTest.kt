package com.opensrs.sync

import com.opensrs.audio.TtsManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins the study-mode -> TTS-locale mapping, including DUAL playback order
 * (Mandarin first, then Cantonese — QUEUE_ADD makes list order audible).
 */
class TtsModeMappingTest {

    @Test
    fun `mandarin mode speaks zh-CN only`() {
        assertEquals(listOf(Locale.SIMPLIFIED_CHINESE), TtsManager.localesFor(com.opensrs.data.local.DialectMode.MANDARIN))
    }

    @Test
    fun `cantonese mode speaks zh-HK only`() {
        assertEquals(listOf(TtsManager.HONG_KONG_CHINESE), TtsManager.localesFor(com.opensrs.data.local.DialectMode.CANTONESE))
    }

    @Test
    fun `dual mode speaks mandarin then cantonese`() {
        assertEquals(
            listOf(Locale.SIMPLIFIED_CHINESE, TtsManager.HONG_KONG_CHINESE),
            TtsManager.localesFor(com.opensrs.data.local.DialectMode.DUAL),
        )
        assertEquals(
            listOf(TtsManager.HONG_KONG_CHINESE, Locale.SIMPLIFIED_CHINESE).reversed(),
            TtsManager.localesFor(com.opensrs.data.local.DialectMode.DUAL),
        )
    }
}
