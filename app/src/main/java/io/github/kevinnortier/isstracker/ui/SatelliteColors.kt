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
import io.github.kevinnortier.isstracker.ui.theme.LocalNightMode

/**
 * A fixed colour per satellite, derived from its NORAD ID so it stays the same when the list is
 * reordered. Mid-tone colours that read on both light and dark backgrounds.
 */
object SatelliteColors {
    private val palette = listOf(
        Color(0xFF1E88E5), // blue (the ISS, 25544)
        Color(0xFFFB8C00), // orange
        Color(0xFF43A047), // green
        Color(0xFFE53935), // red
        Color(0xFF8E24AA), // purple
        Color(0xFF00897B), // teal
        Color(0xFFD81B60), // pink
        Color(0xFF3949AB), // indigo
    )

    fun forNoradId(noradId: Int): Color = palette[Math.floorMod(noradId, palette.size)]
}

/** A small coloured dot identifying a satellite. In night mode it's red like everything else. */
@Composable
fun SatelliteDot(noradId: Int, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = if (LocalNightMode.current) MaterialTheme.colorScheme.primary else SatelliteColors.forNoradId(noradId)
    Box(
        modifier
            .size(size)
            .background(color, CircleShape),
    )
}
