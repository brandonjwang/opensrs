package com.opensrs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -- Brand palette: cinnabar seal red (朱砂) + ink + jade -------------------------

private val Cinnabar = Color(0xFFB3271E)
private val CinnabarDim = Color(0xFF8C1D16)
private val CinnabarBright = Color(0xFFFF6F60)
private val Jade = Color(0xFF00695C)
private val JadeBright = Color(0xFF64D8CB)
private val GoldAccent = Color(0xFFB8860B)
private val GoldBright = Color(0xFFFFD54F)
private val InkDark = Color(0xFF1A1614)
private val PaperLight = Color(0xFFFCF8F2)

private val LightColors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Jade,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB4F1E8),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = GoldAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08D),
    onTertiaryContainer = Color(0xFF261A00),
    background = PaperLight,
    surface = PaperLight,
    surfaceVariant = Color(0xFFF3EAE4),
    onSurfaceVariant = Color(0xFF52443D),
    outline = Color(0xFF85736A),
)

private val DarkColors = darkColorScheme(
    primary = CinnabarBright,
    onPrimary = Color(0xFF5F0107),
    primaryContainer = Color(0xFF7F0010),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = JadeBright,
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005049),
    onSecondaryContainer = Color(0xFFB4F1E8),
    tertiary = GoldBright,
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFE08D),
    background = InkDark,
    surface = InkDark,
    surfaceVariant = Color(0xFF332B27),
    onSurfaceVariant = Color(0xFFD8C2BA),
    outline = Color(0xFFA08D84),
)

// -- Typography: display sizes tuned for large CJK headwords ----------------------

private val AppTypography = Typography(
    // The flashcard headword — big, bold; system CJK fallback renders the glyphs.
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 72.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 52.sp,
        lineHeight = 60.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun OpenSrsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
