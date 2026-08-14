package com.muse.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily

data class MuseStyle(
    val isClaude: Boolean = false,
    val brandSerif: FontFamily = FontFamily.Default,
    val accentGradient: Brush = Brush.horizontalGradient(listOf(Color(0xFFD97757), Color(0xFFE89D77))),
)

val LocalPalette = staticCompositionLocalOf { Cream }
val LocalMuseStyle = staticCompositionLocalOf { MuseStyle() }

val MuseRadius = 20.dp

@Composable
fun MuseTheme(
    mode: MuseThemeMode = MuseThemeMode.Cream,
    content: @Composable () -> Unit,
) {
    val darkSystem = isSystemInDarkTheme()
    val palette = when (mode) {
        MuseThemeMode.Cream -> Cream
        MuseThemeMode.Mocha -> Mocha
        MuseThemeMode.Latte -> Latte
        MuseThemeMode.ClaudeLight -> ClaudeLight
        MuseThemeMode.ClaudeDark -> ClaudeDark
        MuseThemeMode.System -> if (darkSystem) Mocha else Cream
    }
    val isClaude = mode == MuseThemeMode.ClaudeLight || mode == MuseThemeMode.ClaudeDark ||
        (palette.name.startsWith("Claude"))
    val museStyle = MuseStyle(
        isClaude = isClaude,
        brandSerif = if (isClaude) FontFamily.Serif else FontFamily.Default,
        accentGradient = if (isClaude) {
            Brush.horizontalGradient(listOf(Color(0xFFD97757), Color(0xFFE89D77)))
        } else {
            Brush.horizontalGradient(listOf(palette.mauve, palette.lavender))
        },
    )
    val scheme = if (palette.dark) {
        darkColorScheme(
            primary = palette.mauve,
            onPrimary = palette.crust,
            secondary = palette.lavender,
            onSecondary = palette.crust,
            tertiary = palette.teal,
            background = palette.crust,
            onBackground = palette.text,
            surface = palette.base,
            onSurface = palette.text,
            surfaceVariant = palette.surface0,
            onSurfaceVariant = palette.subtext0,
            error = palette.red,
            onError = palette.crust,
            outline = palette.surface1,
            outlineVariant = palette.surface0,
            inverseSurface = palette.text,
            inverseOnSurface = palette.crust,
        )
    } else {
        lightColorScheme(
            primary = palette.mauve,
            onPrimary = Color.White,
            secondary = palette.lavender,
            onSecondary = Color.White,
            tertiary = palette.teal,
            background = palette.crust,
            onBackground = palette.text,
            surface = palette.base,
            onSurface = palette.text,
            surfaceVariant = palette.surface0,
            onSurfaceVariant = palette.subtext0,
            error = palette.red,
            onError = Color.White,
            outline = palette.surface1,
            outlineVariant = palette.surface0,
            inverseSurface = palette.text,
            inverseOnSurface = palette.base,
        )
    }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalMuseStyle provides museStyle,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
