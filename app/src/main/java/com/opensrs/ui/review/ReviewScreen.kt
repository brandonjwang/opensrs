package com.opensrs.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensrs.audio.TtsManager
import com.opensrs.data.db.CardState
import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.RomanizationPref
import com.opensrs.srs.SrsScheduler

@Composable
fun ReviewScreen(viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory)) {
    val ui by viewModel.ui.collectAsState()
    val availability by viewModel.availability.collectAsState()

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        ui.sessionDone || ui.queue.isEmpty() -> SessionDoneContent(
            hasCards = ui.queue.isNotEmpty(),
            onRestart = { viewModel.restartSession() },
        )

        else -> Column(Modifier.fillMaxSize()) {
            SessionProgressBar(
                total = ui.queue.size,
                done = ui.currentIndex,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ReviewCard(
                ui = ui,
                availability = availability,
                onReveal = viewModel::reveal,
                onRate = viewModel::rate,
                onSetDialect = viewModel::setDialectMode,
                onToggleRomanization = viewModel::toggleRomanization,
                onReplay = viewModel::replayAudio,
                onUndo = viewModel::undo,
                onMarkKnown = viewModel::markKnown,
            )
        }
    }
}

@Composable
private fun SessionProgressBar(total: Int, done: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.weight(1f),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            "$done/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewCard(
    ui: ReviewUiState,
    availability: TtsManager.Availability,
    onReveal: () -> Unit,
    onRate: (Int) -> Unit,
    onSetDialect: (DialectMode) -> Unit,
    onToggleRomanization: () -> Unit,
    onReplay: () -> Unit,
    onUndo: () -> Unit,
    onMarkKnown: () -> Unit,
) {
    val word = ui.currentWord ?: return
    val settings = ui.settings ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // -- Control chips -------------------------------------------------------
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            listOf(
                "普通话" to DialectMode.MANDARIN,
                "粵語" to DialectMode.CANTONESE,
                "Dual" to DialectMode.DUAL,
            ).forEach { (label, mode) ->
                FilterChip(
                    selected = settings.dialectMode == mode,
                    onClick = { onSetDialect(mode) },
                    label = {
                        val voiceMissing = availability.checked && when (mode) {
                            DialectMode.MANDARIN -> !availability.mandarinReady
                            DialectMode.CANTONESE -> !availability.cantoneseReady
                            DialectMode.DUAL -> !availability.mandarinReady || !availability.cantoneseReady
                        }
                        Text(if (voiceMissing) "$label ✗" else label)
                    },
                )
            }
            AssistChip(
                onClick = onToggleRomanization,
                label = {
                    Text(
                        when (settings.romanization) {
                            RomanizationPref.PINYIN -> "Pinyin"
                            RomanizationPref.JYUTPING -> "Jyutping"
                        },
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.GTranslate, null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                },
            )
            AssistChip(
                onClick = onReplay,
                label = { Text("Play") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.VolumeUp, null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                },
            )
        }
        // Warn loudly when the selected mode has no voice: Android TTS would
        // otherwise silently fall back to a different language.
        val voiceMissing = availability.checked && when (settings.dialectMode) {
            DialectMode.MANDARIN -> !availability.mandarinReady
            DialectMode.CANTONESE -> !availability.cantoneseReady
            DialectMode.DUAL -> !availability.mandarinReady || !availability.cantoneseReady
        }
        if (voiceMissing) {
            Text(
                when (settings.dialectMode) {
                    DialectMode.MANDARIN ->
                        "No Mandarin voice found — install Google Speech Services and set zh-CN."
                    DialectMode.CANTONESE ->
                        "No Cantonese voice found — audio is suppressed rather than speaking Mandarin. " +
                            "Install a Chinese (Hong Kong) voice in your TTS app (e.g. Google Speech Services)."
                    DialectMode.DUAL ->
                        "Missing a voice for one dialect — install Chinese voices in your TTS app."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // -- Flashcard -----------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
                .animateContentSize(animationSpec = tween(200))
                .pointerInput(ui.revealed) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        if (ui.revealed && kotlin.math.abs(dragAmount) > 60f) {
                            if (dragAmount > 0f) onRate(SrsScheduler.RATING_GOOD)
                            else onRate(SrsScheduler.RATING_AGAIN)
                        }
                    }
                }
                .clickable(enabled = !ui.revealed) { onReveal() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // State badge, top-start corner.
                ui.currentState?.let { st ->
                    if (st.state != CardState.NEW) {
                        Surface(
                            shape = CircleShape,
                            color = if (st.state == CardState.LEARNING) {
                                MaterialTheme.colorScheme.tertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            modifier = Modifier.align(Alignment.TopStart),
                        ) {
                            Text(
                                if (st.state == CardState.LEARNING) "Learning" else "Review",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (st.state == CardState.LEARNING) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                // HSK band badge, top-end corner.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(
                        "HSK ${word.hskLevel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                val primaryScript = if (settings.emphasizeTraditional) word.traditional else word.simplified
                val secondaryScript = if (settings.emphasizeTraditional) word.simplified else word.traditional
                Text(
                    primaryScript,
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    secondaryScript,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    AnimatedVisibility(
                        visible = ui.revealed,
                        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 },
                        exit = fadeOut(),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val primary = when (settings.romanization) {
                                RomanizationPref.PINYIN -> word.pinyin
                                RomanizationPref.JYUTPING -> word.jyutping
                            }.trim().ifEmpty { word.pinyin.trim() }
                            val secondary = when (settings.romanization) {
                                RomanizationPref.PINYIN -> word.jyutping
                                RomanizationPref.JYUTPING -> word.pinyin
                            }.trim()
                            Text(primary, style = MaterialTheme.typography.titleLarge)
                            if (secondary.isNotEmpty()) {
                                Text(
                                    secondary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(word.english, textAlign = TextAlign.Center)

                            val freqLabel = buildString {
                                word.mandarinRank?.let { append("普 #$it") }
                                word.cantoneseRank?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("粵 #$it")
                                }
                            }
                            if (freqLabel.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    freqLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = !ui.revealed, exit = fadeOut()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.TouchApp, null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tap to reveal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        // -- Answer / rating buttons ----------------------------------------------
        if (!ui.revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Show answer")
            }

        } else {
            RatingRow(
                previews = ui.intervalPreview,
                onRate = onRate,
                onUndo = onUndo,
                canUndo = ui.canUndo,
            )
        }

        // -- Skip ------------------------------------------------------------------
        androidx.compose.material3.TextButton(onClick = onMarkKnown) {
            Text(
                "I already know this word",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private data class RatingSpec(
    val label: String,
    val icon: ImageVector,
    val rating: Int,
    val container: Color,
    val content: Color,
)

@Composable
private fun RatingRow(
    previews: List<String>,
    onRate: (Int) -> Unit,
    onUndo: () -> Unit,
    canUndo: Boolean,
) {
    val specs = listOf(
        RatingSpec("Again", Icons.Filled.ThumbDown, SrsScheduler.RATING_AGAIN,
            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer),
        RatingSpec("Hard", Icons.Filled.Refresh, SrsScheduler.RATING_HARD,
            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
        RatingSpec("Good", Icons.Filled.ThumbUp, SrsScheduler.RATING_GOOD,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer),
        RatingSpec("Easy", Icons.Filled.FastForward, SrsScheduler.RATING_EASY,
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        specs.forEachIndexed { idx, spec ->
            Button(
                onClick = { onRate(spec.rating) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = spec.container,
                    contentColor = spec.content,
                ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp, vertical = 8.dp,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(spec.icon, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(spec.label, style = MaterialTheme.typography.labelSmall)
                    Text(
                        previews.getOrElse(idx) { "" },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedButton(
        onClick = onUndo,
        enabled = canUndo,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("Undo last answer")
    }
}

@Composable
private fun SessionDoneContent(hasCards: Boolean, onRestart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (hasCards) Icons.Outlined.Celebration else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (hasCards) "Session complete!" else "No cards scheduled.",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasCards) "Come back tomorrow for more." else "Adjust your daily new-card limit in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRestart) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Refresh queue")
        }
    }
}
