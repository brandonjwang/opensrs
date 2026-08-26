package com.opensrs.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opensrs.data.db.WordDao
import com.opensrs.data.db.WordEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Browse/search over the whole dictionary. Debounced live search on the query;
 * empty query shows the highest-frequency words for context.
 */
@Composable
fun BrowseScreen(searchIndex: kotlinx.coroutines.Deferred<com.opensrs.data.db.WordSearchIndex>) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WordEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // Initial listing; subsequent searches are debounced per keystroke below.
    LaunchedEffect(Unit) {
        val index = searchIndex.await()
        results = index.top(50)
    }
    LaunchedEffect(query) {
        if (query.isEmpty()) return@LaunchedEffect
        delay(250) // debounce
        val index = searchIndex.await() // instant once built; suspends only on first entry
        results = index.search(query.trim())
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search 汉字 / pinyin / jyutping / English") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "${results.size} results",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { word ->
                WordRow(word)
            }
        }
    }
}

@Composable
private fun WordRow(word: WordEntity) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.simplified, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        word.traditional,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "HSK ${word.hskLevel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val roman = buildString {
                    append(word.pinyin.trim())
                    if (word.jyutping.isNotBlank()) append(" · ${word.jyutping.trim()}")
                }
                Text(
                    roman,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    word.english,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
