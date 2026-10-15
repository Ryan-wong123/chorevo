package com.example.chorevo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = Teal900,
    primaryContainer = Teal700,
    onPrimaryContainer = Teal200,
    secondary = Magenta300,
    onSecondary = Magenta900,
    secondaryContainer = Color(0xFF5C1235),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Violet300,
    onTertiary = Color(0xFF22106B),
    background = Ink950,
    onBackground = Ink50,
    surface = Ink900,
    onSurface = Ink50,
    surfaceVariant = Ink750,
    onSurfaceVariant = Ink300,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink850,
    surfaceContainer = Ink800,
    surfaceContainerHigh = Ink750,
    surfaceContainerHighest = Ink700,
    surfaceBright = Ink700,
    surfaceDim = Ink950,
    inverseSurface = Ink100,
    inverseOnSurface = Ink900,
    inversePrimary = Teal700,
    outline = Ink600,
    outlineVariant = Ink750,
    error = Red300,
    onError = Color(0xFF4A0000),
    scrim = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Teal700,
    onPrimary = Paper,
    primaryContainer = Teal200,
    onPrimaryContainer = Teal900,
    secondary = Magenta600,
    onSecondary = Paper,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Magenta900,
    tertiary = Violet600,
    onTertiary = Paper,
    background = Mist50,
    onBackground = Ink900,
    surface = Paper,
    onSurface = Ink900,
    surfaceVariant = Mist200,
    onSurfaceVariant = Mist600,
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Mist50,
    surfaceContainer = Mist100,
    surfaceContainerHigh = Mist200,
    surfaceContainerHighest = Mist300,
    surfaceBright = Paper,
    surfaceDim = Mist300,
    inverseSurface = Ink800,
    inverseOnSurface = Ink50,
    inversePrimary = Teal300,
    outline = Mist400,
    outlineVariant = Mist200,
    error = Red700,
    onError = Paper,
    scrim = Color.Black
)

/**
 * Brand colours that Material's colour scheme has no slot for. Read them with
 * `ChorevoTheme.colors` inside composables.
 */
@Immutable
data class ChorevoColors(
    /** Brand teal, tuned for contrast on the current background. */
    val accent: Color,
    /** Content drawn on [accent]. */
    val onAccent: Color,
    /** Heart / like colour. */
    val like: Color,
    /** Completed / positive state. */
    val success: Color,
    /** Start and end of the signature brand gradient. */
    val gradientStart: Color,
    val gradientEnd: Color,
    /** Content drawn on the brand gradient. */
    val onGradient: Color,
    /** Frosted-glass fill and hairline border for floating surfaces. */
    val glass: Color,
    val glassBorder: Color,
    /** Text and icons drawn over video. */
    val onMedia: Color
) {
    /** Teal → magenta gradient used for hero buttons and highlights. */
    val brandGradient: Brush
        get() = Brush.linearGradient(listOf(gradientStart, gradientEnd))
}

private val DarkChorevoColors = ChorevoColors(
    accent = Teal300,
    onAccent = Teal900,
    like = Magenta400,
    success = Green400,
    gradientStart = Color(0xFF14D8E6),
    gradientEnd = Magenta400,
    onGradient = Color.White,
    glass = Color(0xCC1C1C27),
    glassBorder = Color(0x1FFFFFFF),
    onMedia = Color.White
)

private val LightChorevoColors = ChorevoColors(
    accent = Teal700,
    onAccent = Paper,
    like = Magenta600,
    success = Green700,
    gradientStart = Color(0xFF00A9B8),
    gradientEnd = Magenta600,
    onGradient = Color.White,
    glass = Color(0xE6FFFFFF),
    glassBorder = Color(0x14000000),
    onMedia = Color.White
)

private val LocalChorevoColors = staticCompositionLocalOf { DarkChorevoColors }

/** Entry point for Chorevo-specific design tokens. */
object ChorevoTheme {
    val colors: ChorevoColors
        @Composable @ReadOnlyComposable get() = LocalChorevoColors.current
}

/** Applies the Chorevo design system: colours, typography and shapes. */
@Composable
fun ChorevoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the brand palette is consistent on every device.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalChorevoColors provides if (darkTheme) DarkChorevoColors else LightChorevoColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ChorevoShapes,
            content = content
        )
    }
}
