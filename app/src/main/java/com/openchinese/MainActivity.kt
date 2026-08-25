package com.openchinese

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import com.openchinese.ui.review.ReviewRoute
import com.openchinese.ui.review.ReviewScreen
import com.openchinese.ui.settings.SettingsRoute
import com.openchinese.ui.settings.SettingsScreen
import com.openchinese.ui.theme.OpenChineseTheme

object Routes {
    const val REVIEW = "review"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenChineseTheme {
                OpenChineseAppUi()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenChineseAppUi() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.REVIEW

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (route == Routes.SETTINGS) "Settings" else "Open Chinese") },
                actions = {
                    IconButton(onClick = {
                        if (route == Routes.SETTINGS) {
                            navController.popBackStack()
                        } else {
                            navController.navigate(Routes.SETTINGS)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
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
