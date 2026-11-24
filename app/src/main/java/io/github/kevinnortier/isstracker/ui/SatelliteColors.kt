package io.github.kevinnortier.isstracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.ui.theme.LocalNightMode

/**
 * Satellite colours, indexed by [Satellite.colorIndex]. Mid-tone colours that read on both light and dark
 * backgrounds, ordered so the first few (what most people track) are the most different; the similar
 * pairs (blue/indigo, red/pink, green/teal) are far apart.
 */
object SatelliteColors {
    private val palette = listOf(
        Color(0xFF1E88E5), // blue (the ISS)
        Color(0xFFFB8C00), // orange
        Color(0xFF43A047), // green
        Color(0xFF8E24AA), // purple
        Color(0xFFE53935), // red
        Color(0xFF00897B), // teal
        Color(0xFFD81B60), // pink
        Color(0xFF3949AB), // indigo
    )

    /** For satellites that aren't tracked (and so have no colour). */
    private val untracked = Color(0xFF9E9E9E)

    init {
        check(palette.size == Satellite.COLOR_COUNT) { "Palette size must match Satellite.COLOR_COUNT" }
    }

    fun forIndex(colorIndex: Int?): Color = colorIndex?.let { palette.getOrNull(it) } ?: untracked
}

/**
 * A small dot in a satellite's colour ([Satellite.colorIndex]; grey when null, e.g. not tracked).
 * In night mode it's red like everything else.
 */
@Composable
fun SatelliteDot(colorIndex: Int?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = if (LocalNightMode.current) MaterialTheme.colorScheme.primary else SatelliteColors.forIndex(colorIndex)
    Box(
        modifier
            .size(size)
            .background(color, CircleShape),
    )
}
