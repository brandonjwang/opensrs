package com.opensrs.ui.stats

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opensrs.data.repo.StatsRepository
import com.opensrs.data.repo.StudyStats

/** Lightweight stats screen: pool composition + 7-day due forecast. */
@Composable
fun StatsScreen(statsRepository: StatsRepository) {
    var stats by remember { mutableStateOf<StudyStats?>(null) }

    LaunchedEffect(Unit) {
        stats = statsRepository.load()
    }

    val s = stats
    if (s == null) {
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
        Section("Card pool") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Learning", s.learningCount.toString(), Modifier.weight(1f))
            StatCard("Young", (s.graduatedCount - s.matureCount).coerceAtLeast(0).toString(), Modifier.weight(1f))
            StatCard("Mature", s.matureCount.toString(), Modifier.weight(1f))
        }
        }

        HorizontalDivider()

        Section("7-day forecast") {
            ForecastChart(s.forecast)
        }

        HorizontalDivider()

        Section("All time") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Answers", s.totalReviews.toString(), Modifier.weight(1f))
            StatCard("Lapses", s.totalLapses.toString(), Modifier.weight(1f))
            val retention = if (s.totalReviews > 0) {
                ((s.totalReviews - s.totalLapses) * 100 / s.totalReviews)
            } else {
                100
            }
            StatCard("Retention", "$retention%", Modifier.weight(1f))
        }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ForecastChart(forecast: List<Int>) {
    val max = (forecast.maxOrNull() ?: 0).coerceAtLeast(1)
    val dayLabels = listOf("Today", "+1d", "+2d", "+3d", "+4d", "+5d", "+6d")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            forecast.forEach { count ->
                val frac = count.toFloat() / max
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth()
                            .height((frac * 80).dp.coerceAtLeast(4.dp)),
                    ) {
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxSize(),
                        ) {}
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dayLabels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
