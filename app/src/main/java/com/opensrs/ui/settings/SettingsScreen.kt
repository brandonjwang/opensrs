package com.opensrs.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensrs.data.local.DialectMode
import com.opensrs.data.local.RomanizationPref
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val account by viewModel.account.collectAsState()
    val signIn by viewModel.signIn.collectAsState()
    val context = LocalContext.current

    // Interactive consent launcher for the Google sign-in flow.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onConsentResult(result.data)
    }

    // When the ViewModel emits a one-shot consent intent, launch it exactly once.
    val consentIntent = signIn.consentIntent
    androidx.compose.runtime.LaunchedEffect(consentIntent) {
        consentIntent?.let { consentLauncher.launch(it) }
    }

    if (settings == null) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // -- Study limits ---------------------------------------------------------
        SectionTitle("Daily limits")
        Text("New cards per day: ${settings!!.dailyNewLimit}")
        Slider(
            value = settings!!.dailyNewLimit.toFloat(),
            onValueChange = { viewModel.setDailyNewLimit(it.toInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
        Text("Reviews per day: ${settings!!.dailyReviewLimit}")
        Slider(
            value = settings!!.dailyReviewLimit.toFloat(),
            onValueChange = { viewModel.setDailyReviewLimit(it.toInt()) },
            valueRange = 20f..500f,
        )

        HorizontalDivider()

        // -- Dialect & audio -------------------------------------------------------
        SectionTitle("Speech")
        LabeledChoiceRow(
            label = "Dialect",
            options = listOf(
                "Mandarin" to (settings!!.dialectMode == DialectMode.MANDARIN),
                "Cantonese" to (settings!!.dialectMode == DialectMode.CANTONESE),
                "Dual" to (settings!!.dialectMode == DialectMode.DUAL),
            ),
            onSelect = { idx ->
                viewModel.setDialectMode(
                    when (idx) {
                        0 -> DialectMode.MANDARIN
                        1 -> DialectMode.CANTONESE
                        else -> DialectMode.DUAL
                    },
                )
            },
        )
        LabeledChoiceRow(
            label = "Romanization",
            options = listOf(
                "Pinyin" to (settings!!.romanization == RomanizationPref.PINYIN),
                "Jyutping" to (settings!!.romanization == RomanizationPref.JYUTPING),
            ),
            onSelect = { idx ->
                viewModel.setRomanization(
                    if (idx == 0) RomanizationPref.PINYIN else RomanizationPref.JYUTPING,
                )
            },
        )
        ToggleRow(
            label = "Auto-play pronunciation on reveal",
            checked = settings!!.autoPlayTts,
            onChange = { viewModel.setAutoPlayTts(it) },
        )

        HorizontalDivider()

        // -- Sync ------------------------------------------------------------------
        SectionTitle("Google Drive backup")
        account?.let { Text("Account: $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            text = buildString {
                append(syncStatus.lastMessage)
                syncStatus.lastSyncAt?.let {
                    append(" · ")
                    append(DateFormat.getDateTimeInstance().format(Date(it)))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account == null) {
                Button(
                    onClick = { viewModel.signIn() },
                    enabled = !signIn.inFlight,
                ) {
                    if (signIn.inFlight) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).fillMaxWidth(0.2f))
                    } else {
                        Text("Sign in")
                    }
                }
            } else {
                Button(onClick = { viewModel.syncNow() }, enabled = !syncStatus.running) {
                    Text(if (syncStatus.running) "Syncing…" else "Sync now")
                }
                OutlinedButton(onClick = { viewModel.signOut() }) { Text("Sign out") }
            }
        }
        signIn.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Backups go to a hidden app-private folder on your own Drive. Only review history is stored — never the dictionary.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LabeledChoiceRow(
    label: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { idx, (name, selected) ->
                if (selected) {
                    Button(onClick = { onSelect(idx) }) { Text(name) }
                } else {
                    OutlinedButton(onClick = { onSelect(idx) }) { Text(name) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
