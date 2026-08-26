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

    private fun scopeChanged(old: UserSettings, new: UserSettings): Boolean =
        old.dailyNewLimit != new.dailyNewLimit ||
            old.dailyReviewLimit != new.dailyReviewLimit ||
            old.hskMaxLevel != new.hskMaxLevel ||
            old.hskMinLevel != new.hskMinLevel ||
            old.prefersCantonese != new.prefersCantonese

    private suspend fun startSession(settings: UserSettings) {
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
        _ui.value = ReviewUiState(
            loading = false,
            queue = queue,
            words = words,
            settings = settings,
        )
        if (queue.isNotEmpty()) autoPlay()
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

    /** Rating buttons: 0=Again 1=Hard 2=Good 3=Easy. */
    fun rate(rating: Int) {
        val s = _ui.value
        val state = s.currentState ?: return
        viewModelScope.launch {
            repository.answer(state, rating)
            lastAnswer = s.currentIndex to state
            _ui.value = _ui.value.copy(canUndo = true)
            advance()
        }
    }
    /** Permanently marks the current word as known and moves on. Not undoable. */
    fun markKnown() {
        val idx = _ui.value.currentIndex
        val state = _ui.value.currentState ?: return
        viewModelScope.launch {
            repository.markKnown(state)
            // A second tap raced ahead of recomposition: its card was already
            // advanced past — do not advance twice (that would skip a card).
            if (_ui.value.currentIndex == idx) advance()
        }
    }

    /** Reverts the most recent answer and steps back to that card. */
    fun undo() {
        val (idx, prevState) = lastAnswer ?: return
        viewModelScope.launch {
            repository.undo(prevState)
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

    /** Explicit mode selection: focus Mandarin, Cantonese, or hear both. */
    fun setDialectMode(mode: DialectMode) {
        val s = _ui.value
        val settings = s.settings ?: return
        if (settings.dialectMode == mode) return
        _ui.value = s.copy(settings = settings.copy(dialectMode = mode))
        viewModelScope.launch { preferences.setDialectMode(mode) }
    }

    fun toggleRomanization() {
        val s = _ui.value
        val settings = s.settings ?: return
        val next = when (settings.romanization) {
            RomanizationPref.PINYIN -> RomanizationPref.JYUTPING
            RomanizationPref.JYUTPING -> RomanizationPref.PINYIN
        }
        _ui.value = s.copy(settings = settings.copy(romanization = next))
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
