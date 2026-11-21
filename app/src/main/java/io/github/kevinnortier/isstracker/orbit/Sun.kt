package io.github.kevinnortier.isstracker.orbit

import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-precision sun position (Astronomical Almanac formula, accurate to about 0.01°) and
 * Earth-shadow test. Good enough to decide whether a satellite is sunlit and whether the
 * observer's sky is dark.
 */
object Sun {

    private const val DEG2RAD = PI / 180.0
    private const val AU_KM = 149_597_870.7
    private const val EARTH_RADIUS_KM = 6378.137

    /** Sun elevation below which the sky counts as dark enough to see the ISS (civil twilight). */
    const val DARK_SKY_SUN_ELEVATION_DEG = -6.0

    /** Sun position (km) in the equator-and-equinox-of-date frame (≈ TEME, differs by < 1"). */
    fun positionTeme(time: Instant): Vector3 {
        val n = Frames.julianDate(time) - 2451545.0
        val meanLongitude = (280.460 + 0.9856474 * n) * DEG2RAD
        val meanAnomaly = (357.528 + 0.9856003 * n) * DEG2RAD
        val eclipticLongitude = meanLongitude +
            (1.915 * sin(meanAnomaly) + 0.020 * sin(2.0 * meanAnomaly)) * DEG2RAD
        val obliquity = (23.439 - 0.0000004 * n) * DEG2RAD
        val distanceKm = (1.00014 - 0.01671 * cos(meanAnomaly) - 0.00014 * cos(2.0 * meanAnomaly)) * AU_KM
        return Vector3(
            distanceKm * cos(eclipticLongitude),
            distanceKm * cos(obliquity) * sin(eclipticLongitude),
            distanceKm * sin(obliquity) * sin(eclipticLongitude),
        )
    }

    /** Azimuth and elevation (degrees) of the sun seen from [observer]. */
    fun lookAngles(observer: GeodeticLocation, time: Instant): Pair<Double, Double> {
        val sunEcef = Frames.temeToEcef(positionTeme(time), time)
        return Topocentric.lookAnglesEcef(observer, sunEcef - observer.ecef)
    }

    /** Sun elevation (degrees) seen from [observer]. */
    fun elevationDeg(observer: GeodeticLocation, time: Instant): Double = lookAngles(observer, time).second

    /**
     * True if a satellite at [satelliteTeme] (km) is in sunlight, using a cylindrical Earth shadow.
     * [sunTeme] can be passed in to avoid recomputing the sun position.
     */
    fun isSunlit(satelliteTeme: Vector3, time: Instant, sunTeme: Vector3 = positionTeme(time)): Boolean {
        val sunDir = sunTeme.normalized()
        val alongSun = satelliteTeme dot sunDir
        if (alongSun >= 0.0) return true // on the day side of the Earth
        val distanceFromShadowAxis = (satelliteTeme - sunDir * alongSun).magnitude
        return distanceFromShadowAxis > EARTH_RADIUS_KM
    }
}
