package com.opensrs.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import com.opensrs.data.local.DialectMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wrapper around Android's native [TextToSpeech] handling Mandarin (zh-CN) and
 * Cantonese (zh-HK) engines, availability checks, and queued playback.
 *
 * Lifecycle: create once per app (see AppContainer), call [speakWord] from UI,
 * [shutdown] when the process is torn down.
 */
class TtsManager(context: Context) {

    /** Availability of each dialect on this device, updated after engine init. */
    data class Availability(
        val mandarinReady: Boolean = false,
        val cantoneseReady: Boolean = false,
        val missingVoiceHint: String? = null,
    )

    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _availability = MutableStateFlow(Availability())
    val availability: StateFlow<Availability> = _availability

    /**
     * Engines are created lazily and in parallel; each gets its own instance so
     * language switches never race between dialects.
     */
    private val engines = mutableMapOf<Locale, TextToSpeech>()

    private val utteranceCounter = AtomicInteger(0)

    init {
        initEngine(Locale.SIMPLIFIED_CHINESE)
        initEngine(HONG_KONG_CHINESE)
    }

    private fun initEngine(locale: Locale) {
        // lateinit lets the init callback reference the engine instance safely;
        // the callback fires asynchronously on a binder thread.
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            val ready = status == TextToSpeech.SUCCESS && isLanguageAvailable(engine, locale)
            publishAvailability(locale, ready)
        }
        synchronized(engines) { engines[locale] = engine }
    }

    /** setLanguage doubles as priming; result codes decide availability. */
    private fun isLanguageAvailable(tts: TextToSpeech, locale: Locale): Boolean =
        tts.setLanguage(locale).let { code ->
            code != TextToSpeech.LANG_MISSING_DATA && code != TextToSpeech.LANG_NOT_SUPPORTED
        }

    private fun publishAvailability(locale: Locale, ready: Boolean) {
        scope.launch {
            val current = _availability.value
            _availability.value = when (locale) {
                Locale.SIMPLIFIED_CHINESE -> current.copy(mandarinReady = ready)
                else -> current.copy(cantoneseReady = ready)
            }.let { next ->
                if (!next.mandarinReady && !next.cantoneseReady) {
                    next.copy(missingVoiceHint = "No Chinese TTS voice found. Install Google Speech Services.")
                } else {
                    next.copy(missingVoiceHint = null)
                }
            }
        }
    }

    /**
     * Plays one word per [mode].
     * MANDARIN → zh-CN only; CANTONESE → zh-HK only; DUAL → zh-CN then zh-HK.
     *
     * @param text word to pronounce (logographic script works for both dialects).
     */
    fun speakWord(text: String, mode: DialectMode) {
        when (mode) {
            DialectMode.MANDARIN -> speak(text, Locale.SIMPLIFIED_CHINESE)
            DialectMode.CANTONESE -> speak(text, HONG_KONG_CHINESE)
            DialectMode.DUAL -> {
                // QUEUE mode guarantees sequential playback across utterances.
                speak(text, Locale.SIMPLIFIED_CHINESE)
                speak(text, HONG_KONG_CHINESE)
            }
        }
    }

    /** Reveal callback: auto-play hook respecting user preference. */
    fun autoPlayIfEnabled(enabled: Boolean, text: String, mode: DialectMode) {
        if (enabled) speakWord(text, mode)
    }

    fun stop() {
        synchronized(engines) { engines.values.forEach { it.stop() } }
    }

    fun shutdown() {
        scope.cancel()
        synchronized(engines) {
            engines.values.forEach { it.shutdown() }
            engines.clear()
        }
    }

    private fun speak(text: String, locale: Locale) {
        val tts = synchronized(engines) { engines[locale] } ?: return
        val utteranceId = "oc-${utteranceCounter.incrementAndGet()}"
        // QUEUE keeps DUAL sequential; identical text needs distinct ids to not collapse.
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    companion object {
        /** Cantonese locale; falls back gracefully when voice missing (see availability). */
        val HONG_KONG_CHINESE: Locale = Locale("zh", "HK")
    }
}
