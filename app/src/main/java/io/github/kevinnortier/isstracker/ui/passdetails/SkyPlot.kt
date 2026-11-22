package io.github.kevinnortier.isstracker.ui.passdetails

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polar sky plot geometry, like a map of the sky: the centre is straight up (zenith), the edge is
 * the horizon, north is up and east is to the right.
 */
object SkyPlot {

    /**
     * Position of a direction in a plot of unit radius: x to the right, y downwards (screen
     * coordinates), both in −1..1. Directions below the horizon land outside the unit circle.
     */
    fun position(azimuthDeg: Double, elevationDeg: Double): Pair<Double, Double> {
        val r = (90.0 - elevationDeg) / 90.0
        val az = azimuthDeg * PI / 180.0
        return r * sin(az) to -r * cos(az)
    }
}
