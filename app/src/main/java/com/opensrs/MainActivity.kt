package com.opensrs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opensrs.ui.review.ReviewScreen
import com.opensrs.ui.settings.SettingsScreen
import com.opensrs.ui.theme.OpenSrsTheme

object Routes {
    const val REVIEW = "review"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenSrsTheme {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (route == Routes.SETTINGS) "Settings" else "Open SRS") },
                actions = {
                    IconButton(onClick = {
                        if (route == Routes.SETTINGS) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Routes.SETTINGS)
                        }
                    }) {
                        Icon(
                            imageVector = if (route == Routes.SETTINGS) {
                                Icons.AutoMirrored.Filled.ArrowBack
                            } else {
                                Icons.Filled.Settings
                            },
                            contentDescription = if (route == Routes.SETTINGS) "Back" else "Settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.REVIEW,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.REVIEW) {
                ReviewScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
        }
    }
}
