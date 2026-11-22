package io.github.kevinnortier.isstracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.kevinnortier.isstracker.data.settings.ThemeMode

/** True if [mode] gives dark backgrounds (decides the status bar icon colour too). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.NIGHT -> true
}

/**
 * The app theme. Light and dark use Material You colours from the wallpaper (always available,
 * since minSdk is 31); night mode uses a fixed red-on-black scheme.
 */
@Composable
fun ISSTrackerTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        mode == ThemeMode.NIGHT -> NightColorScheme
        mode.isDark() -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
