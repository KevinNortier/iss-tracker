package io.github.kevinnortier.isstracker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Night mode: only red on black, so the screen doesn't ruin dark-adapted eyes when observing.
 * Text reds are bright enough for at least 4.5:1 contrast on black; dim the phone itself for night use.
 */
internal val NightColorScheme = darkColorScheme(
    primary = Color(0xFFFF5A4F),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3D0805),
    onPrimaryContainer = Color(0xFFFF7A70),
    secondary = Color(0xFFE53935),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF330605),
    onSecondaryContainer = Color(0xFFFF7A70),
    tertiary = Color(0xFFFF7043),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF3A0B04),
    onTertiaryContainer = Color(0xFFFF8A65),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFF5A4F),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFF5A4F),
    surfaceVariant = Color(0xFF1C0302),
    onSurfaceVariant = Color(0xFFE53935),
    surfaceTint = Color(0xFFFF5A4F),
    inverseSurface = Color(0xFFFF5A4F),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Color(0xFF7A0F09),
    error = Color(0xFFFF8A65),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A0A04),
    onErrorContainer = Color(0xFFFF8A65),
    outline = Color(0xFF8C1D18),
    outlineVariant = Color(0xFF4A0D0A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF240403),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0D0101),
    surfaceContainer = Color(0xFF120202),
    surfaceContainerHigh = Color(0xFF170302),
    surfaceContainerHighest = Color(0xFF1C0302),
)
