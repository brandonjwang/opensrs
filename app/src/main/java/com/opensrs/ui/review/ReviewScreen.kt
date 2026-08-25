package com.opensrs.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

        else -> ReviewCard(
            ui = ui,
            availabilityHint = availability.missingVoiceHint,
            onReveal = viewModel::reveal,
            onRate = viewModel::rate,
            onToggleDialect = viewModel::toggleDialect,
            onToggleRomanization = viewModel::toggleRomanization,
            onReplay = viewModel::replayAudio,
        )
    }
}

@Composable
private fun ReviewCard(
    ui: ReviewUiState,
    availabilityHint: String?,
    onReveal: () -> Unit,
    onRate: (Int) -> Unit,
    onToggleDialect: () -> Unit,
    onToggleRomanization: () -> Unit,
    onReplay: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = onToggleDialect,
                label = {
                    Text(
                        when (settings.dialectMode) {
                            DialectMode.MANDARIN -> "普通话"
                            DialectMode.CANTONESE -> "粵語"
                            DialectMode.DUAL -> "雙語 Dual"
                        },
                    )
                },
            )
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
            )
            AssistChip(onClick = onReplay, label = { Text("🔊 Replay") })
        }

        availabilityHint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        // -- Flashcard -----------------------------------------------------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp)
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
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    word.simplified,
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    word.traditional,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                if (!ui.revealed) {
                    Text(
                        "Tap to reveal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    val primary = when (settings.romanization) {
                        RomanizationPref.PINYIN -> word.pinyin
                        RomanizationPref.JYUTPING -> word.jyutping
                    }
                    val secondary = when (settings.romanization) {
                        RomanizationPref.PINYIN -> word.jyutping
                        RomanizationPref.JYUTPING -> word.pinyin
                    }
                    Text(primary, style = MaterialTheme.typography.titleLarge)
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(word.english, style = MaterialTheme.typography.bodyLarge)

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
        }

        // -- Answer / rating buttons ----------------------------------------------
        if (!ui.revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show answer") }
        } else {
            RatingRow(onRate = onRate)
        }
    }
}

@Composable
private fun RatingRow(onRate: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            Triple("Again", SrsScheduler.RATING_AGAIN, MaterialTheme.colorScheme.error),
            Triple("Hard", SrsScheduler.RATING_HARD, MaterialTheme.colorScheme.tertiary),
            Triple("Good", SrsScheduler.RATING_GOOD, MaterialTheme.colorScheme.primary),
            Triple("Easy", SrsScheduler.RATING_EASY, Color(0xFF2E7D32)),
        ).forEach { (label, rating, color) ->
            Button(
                onClick = { onRate(rating) },
                colors = ButtonDefaults.buttonColors(containerColor = color),
                modifier = Modifier.weight(1f),
            ) { Text(label) }
        }
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
        Text(
            if (hasCards) "Session complete! 🎉" else "No cards scheduled.",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasCards) "Come back tomorrow for more." else "Adjust your daily new-card limit in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRestart) { Text("Refresh queue") }
    }
}
