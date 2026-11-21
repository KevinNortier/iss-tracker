package io.github.kevinnortier.isstracker.orbit

import java.time.Instant

/** Everything about a satellite's position as seen from an observer at one moment. */
data class Observation(
    val time: Instant,
    val look: LookAngles,
    /** True if the satellite is in sunlight (not in Earth's shadow). */
    val sunlit: Boolean,
    /** Sun elevation at the observer (degrees). */
    val sunElevationDeg: Double,
) {
    /** Visible to the naked eye: above the horizon, sunlit, and the observer's sky is dark. */
    val visible: Boolean
        get() = look.elevationDeg > 0.0 && sunlit && sunElevationDeg < Sun.DARK_SKY_SUN_ELEVATION_DEG
}

/** Tracks one satellite from one observer location. */
class SatelliteTracker(tle: Tle, val observer: GeodeticLocation) {

    private val sgp4 = Sgp4(tle)

    val tle: Tle get() = sgp4.tle

    fun observe(time: Instant): Observation {
        val state = sgp4.propagate(time)
        val sun = Sun.positionTeme(time)
        return Observation(
            time = time,
            look = Topocentric.lookAngles(observer, state),
            sunlit = Sun.isSunlit(state.position, time, sun),
            sunElevationDeg = Sun.elevationDeg(observer, time),
        )
    }
}
