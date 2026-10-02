package com.scorebook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF2B2D42)
private val Accent = Color(0xFFEF8354)
private val Paper = Color(0xFFF7F5F0)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE0F2),
    onPrimaryContainer = Color(0xFF151728),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCCD),
    onSecondaryContainer = Color(0xFF3A1606),
    background = Paper,
    onBackground = Color(0xFF1C1B1A),
    surface = Paper,
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFE9E5DC),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainer = Color(0xFFF0EDE6),
    surfaceContainerHigh = Color(0xFFEAE6DE),
    surfaceContainerHighest = Color(0xFFE4E0D7),
    surfaceContainerLow = Color(0xFFF4F1EB),
    outline = Color(0xFF7C786F),
    outlineVariant = Color(0xFFCDC8BD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBEC3E6),
    onPrimary = Color(0xFF22263D),
    primaryContainer = Color(0xFF393D55),
    onPrimaryContainer = Color(0xFFDDE0F2),
    secondary = Color(0xFFFFB596),
    onSecondary = Color(0xFF5A1D00),
    secondaryContainer = Color(0xFF7A3315),
    onSecondaryContainer = Color(0xFFFFDCCD),
    background = Color(0xFF141413),
    onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF141413),
    onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF3A3833),
    onSurfaceVariant = Color(0xFFCCC6BB),
    surfaceContainer = Color(0xFF201F1D),
    surfaceContainerHigh = Color(0xFF2A2927),
    surfaceContainerHighest = Color(0xFF353431),
    surfaceContainerLow = Color(0xFF1B1A19),
    outline = Color(0xFF969187),
    outlineVariant = Color(0xFF4A4740),
)

@Composable
fun ScoreBookTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
