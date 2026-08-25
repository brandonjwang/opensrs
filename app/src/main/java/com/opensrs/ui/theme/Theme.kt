package com.opensrs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SealRed = Color(0xFFB71C1C)
private val SealRedDark = Color(0xFFFF6F60)
private val Jade = Color(0xFF00695C)

private val LightColors = lightColorScheme(
    primary = SealRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Jade,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
)

private val DarkColors = darkColorScheme(
    primary = SealRedDark,
    onPrimary = Color(0xFF5F0013),
    primaryContainer = Color(0xFF7F0030),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2DFDB),
)

@Composable
fun OpenSrsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
