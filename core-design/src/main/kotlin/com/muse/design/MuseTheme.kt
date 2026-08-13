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

val LocalPalette = staticCompositionLocalOf { Mocha }

val MuseRadius = 16.dp

@Composable
fun MuseTheme(
    mode: MuseThemeMode = MuseThemeMode.Mocha,
    content: @Composable () -> Unit,
) {
    val darkSystem = isSystemInDarkTheme()
    val palette = when (mode) {
        MuseThemeMode.Mocha -> Mocha
        MuseThemeMode.Latte -> Latte
        MuseThemeMode.System -> if (darkSystem) Mocha else Latte
    }
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
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
