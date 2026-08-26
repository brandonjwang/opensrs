package com.opensrs.ui.review

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensrs.audio.TtsManager
import com.opensrs.data.db.FlashcardStateEntity
import com.opensrs.data.db.WordEntity
import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.prefersCantonese
import com.opensrs.data.local.PreferencesRepository
import com.opensrs.data.local.RomanizationPref
import com.opensrs.data.local.UserSettings
import com.opensrs.OpenSrsApp
import com.opensrs.data.repo.QueueEntry
import com.opensrs.data.repo.StudyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val queue: List<QueueEntry> = emptyList(),
    val words: Map<Long, WordEntity> = emptyMap(),
    val currentIndex: Int = 0,
    val revealed: Boolean = false,
    val settings: UserSettings? = null,
    val sessionDone: Boolean = false,
    /** Next-interval labels per rating, e.g. ["<1m","10m","1d","4d"]. */
    val intervalPreview: List<String> = emptyList(),
    val canUndo: Boolean = false,
) {
    val currentWord: WordEntity? get() = queue.getOrNull(currentIndex)?.let { words[it.wordId] }
    val currentState: FlashcardStateEntity? get() = queue.getOrNull(currentIndex)?.state
}

class ReviewViewModel(
    private val repository: StudyRepository,
    private val preferences: PreferencesRepository,
    private val tts: TtsManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(ReviewUiState())
    val ui: StateFlow<ReviewUiState> = _ui

    val availability = tts.availability

    init {
        // Live settings: every change reflects immediately. Scope-affecting
        // changes (limits, HSK window, dialect ordering) rebuild the queue on
        // the spot — already-answered progress is persisted, so a rebuild
        // just re-derives what's still due. DataStore conflates rapid slider
        // emissions, and collect suspends during rebuild, so no interleaving.
        viewModelScope.launch {
            preferences.settings.collect { s ->
                val prev = _ui.value.settings
                _ui.value = _ui.value.copy(settings = s)
                when {
                    prev == null -> startSession(s) // first emission
                    scopeChanged(prev, s) -> startSession(s)
                }
            }
        }
    }


    private suspend fun startSession(settings: UserSettings) {
        sessionGeneration++ // invalidates in-flight rate/undo/markKnown UI steps
        lastAnswer = null
        _ui.value = _ui.value.copy(loading = true)
        val queue = repository.buildSession(
            newLimit = settings.dailyNewLimit,
            reviewLimit = settings.dailyReviewLimit,
            preferCantonese = settings.prefersCantonese,
            hskMaxLevel = settings.hskMaxLevel,
            hskMinLevel = settings.hskMinLevel,
        )
        val words = repository.hydrate(queue.map { it.wordId })
        // No autoPlay() here: revealed is always false for a fresh session,
        // and autoplay must not fire on every settings-drag rebuild.
        _ui.value = ReviewUiState(
            loading = false,
            queue = queue,
            words = words,
            settings = settings,
        )
    }

    fun reveal() {
        if (_ui.value.revealed || _ui.value.currentWord == null) return
        _ui.value = _ui.value.copy(
            revealed = true,
            intervalPreview = _ui.value.currentState?.let { repository.previewIntervals(it) } ?: emptyList(),
        )
        autoPlay()
    }

    /** Pre-answer snapshots for one-level undo: (queueIndex, previousState). */
    private var lastAnswer: Pair<Int, FlashcardStateEntity>? = null

    /** Bumped by every session rebuild; in-flight mutations from an older
     *  generation must not touch the UI of the rebuilt session. */
    private var sessionGeneration = 0

    /** Rating buttons: 0=Again 1=Hard 2=Good 3=Easy. */
    fun rate(rating: Int) {
        val s = _ui.value
        val state = s.currentState ?: return
        val gen = sessionGeneration
        viewModelScope.launch {
            repository.answer(state, rating)
            if (gen != sessionGeneration) return@launch // queue rebuilt mid-answer
            lastAnswer = s.currentIndex to state
            _ui.value = _ui.value.copy(canUndo = true)
            advance()
        }
    }

    /** Permanently marks the current word as known and moves on. Not undoable. */
    fun markKnown() {
        val idx = _ui.value.currentIndex
        val state = _ui.value.currentState ?: return
        val gen = sessionGeneration
        viewModelScope.launch {
            repository.markKnown(state)
            // A second tap raced ahead of recomposition, or a settings rebuild
            // replaced the queue: do not advance against a stale position.
            if (gen == sessionGeneration && _ui.value.currentIndex == idx) advance()
        }
    }

    /** Reverts the most recent answer and steps back to that card. */
    fun undo() {
        val (idx, prevState) = lastAnswer ?: return
        val gen = sessionGeneration
        viewModelScope.launch {
            repository.undo(prevState)
            if (gen != sessionGeneration) {
                // The DB restore still applies; the UI it belonged to is gone.
                lastAnswer = null
                return@launch
            }
            lastAnswer = null
            _ui.value = _ui.value.copy(canUndo = false)
            _ui.value = _ui.value.copy(
                currentIndex = idx,
                revealed = true,
                sessionDone = false,
                intervalPreview = repository.previewIntervals(prevState),
            )
        }
    }

    private fun advance() {
        val s = _ui.value
        val next = s.currentIndex + 1
        if (next >= s.queue.size) {
            _ui.value = s.copy(sessionDone = true, revealed = false)
        } else {
            _ui.value = s.copy(currentIndex = next, revealed = false)
            autoPlay()
        }
    }

    /**
     * Explicit mode selection: focus Mandarin, Cantonese, or hear both.
     * Persists only — the settings collector applies the new value, so the UI
     * never fights a queued DataStore emission (rapid-toggle flicker).
     */
    fun setDialectMode(mode: DialectMode) {
        val settings = _ui.value.settings ?: return
        if (settings.dialectMode == mode) return
        viewModelScope.launch { preferences.setDialectMode(mode) }
    }

    fun toggleRomanization() {
        val settings = _ui.value.settings ?: return
        val next = when (settings.romanization) {
            RomanizationPref.PINYIN -> RomanizationPref.JYUTPING
            RomanizationPref.JYUTPING -> RomanizationPref.PINYIN
        }
        viewModelScope.launch { preferences.setRomanization(next) }
    }

    fun replayAudio() {
        val word = _ui.value.currentWord ?: return
        val mode = _ui.value.settings?.dialectMode ?: return
        tts.speakWord(word.simplified, mode)
    }

    private fun autoPlay() {
        val word = _ui.value.currentWord ?: return
        val settings = _ui.value.settings ?: return
        tts.autoPlayIfEnabled(
            enabled = settings.autoPlayTts && _ui.value.revealed,
            text = word.simplified,
            mode = settings.dialectMode,
        )
    }

    fun restartSession() {
        val settings = _ui.value.settings ?: return
        viewModelScope.launch { startSession(settings) }
    }

    companion object {

        /** Settings whose change requires re-deriving the review queue. */
        internal fun scopeChanged(old: UserSettings, new: UserSettings): Boolean =
            old.dailyNewLimit != new.dailyNewLimit ||
                old.dailyReviewLimit != new.dailyReviewLimit ||
                old.hskMaxLevel != new.hskMaxLevel ||
                old.hskMinLevel != new.hskMinLevel ||
                old.prefersCantonese != new.prefersCantonese

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenSrsApp
                ReviewViewModel(
                    repository = app.container.repository,
                    preferences = app.container.preferences,
                    tts = app.container.tts,
                )
            }
        }
    }
}
