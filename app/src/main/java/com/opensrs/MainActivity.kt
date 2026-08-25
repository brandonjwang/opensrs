package com.opensrs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.ui.Alignment
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opensrs.ui.browse.BrowseScreen
import com.opensrs.ui.review.ReviewScreen
import com.opensrs.ui.settings.SettingsScreen
import com.opensrs.ui.stats.StatsScreen
import androidx.compose.ui.Modifier

object Routes {
    const val REVIEW = "review"
    const val BROWSE = "browse"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    val TITLES = mapOf(REVIEW to "Open SRS", BROWSE to "Browse", STATS to "Stats", SETTINGS to "Settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.opensrs.ui.theme.OpenSrsTheme {
                OpenSrsAppUi()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSrsAppUi() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.REVIEW
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as OpenSrsApp).container

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(Routes.TITLES[route] ?: "Open SRS") })
        },
        bottomBar = {
            NavigationBar {
                BottomTab(Icons.Filled.Style, "Review", route == Routes.REVIEW) {
                    if (route != Routes.REVIEW) navController.navigate(Routes.REVIEW) { launchSingleTop = true }
                }
                BottomTab(Icons.Filled.MenuBook, "Browse", route == Routes.BROWSE) {
                    if (route != Routes.BROWSE) navController.navigate(Routes.BROWSE) { launchSingleTop = true }
                }
                BottomTab(Icons.Filled.BarChart, "Stats", route == Routes.STATS) {
                    if (route != Routes.STATS) navController.navigate(Routes.STATS) { launchSingleTop = true }
                }
                BottomTab(Icons.Filled.Settings, "Settings", route == Routes.SETTINGS) {
                    if (route != Routes.SETTINGS) navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.REVIEW,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.REVIEW) { ReviewScreen() }
            composable(Routes.BROWSE) { BrowseScreen(container.wordDao) }
            composable(Routes.STATS) { StatsScreen(container.statsRepository) }
            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = contentColor)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}
