package io.github.kevinnortier.isstracker.orbit

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** An observer's position on Earth (WGS-84). */
data class GeodeticLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double = 0.0,
) {
    init {
        require(latitudeDeg in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitudeDeg in -180.0..180.0) { "Longitude must be between -180 and 180" }
    }

    /** Position in the Earth-fixed frame (km). */
    val ecef: Vector3 by lazy {
        val lat = latitudeDeg * DEG2RAD
        val lon = longitudeDeg * DEG2RAD
        val h = altitudeMeters / 1000.0
        val sinLat = sin(lat)
        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        Vector3(
            (n + h) * cos(lat) * cos(lon),
            (n + h) * cos(lat) * sin(lon),
            (n * (1.0 - WGS84_E2) + h) * sinLat,
        )
    }

    private companion object {
        const val DEG2RAD = PI / 180.0
        const val WGS84_A = 6378.137 // km
        const val WGS84_F = 1.0 / 298.257223563
        const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
    }
}

/** Where to look from the observer. */
data class LookAngles(
    /** Degrees clockwise from true north [0, 360). */
    val azimuthDeg: Double,
    /** Degrees above the horizon (negative = below). Also called altitude. */
    val elevationDeg: Double,
    /** Distance to the target (km). */
    val rangeKm: Double,
    /** Rate of change of the distance (km/s). Positive = moving away. */
    val rangeRateKmPerSec: Double,
)

object Topocentric {

    private const val RAD2DEG = 180.0 / PI
    private const val DEG2RAD = PI / 180.0

    /** Look angles from [observer] to a satellite given its TEME state. */
    fun lookAngles(observer: GeodeticLocation, state: StateVector): LookAngles {
        val ecef = Frames.temeToEcef(state)
        val rho = ecef.position - observer.ecef
        val range = rho.magnitude
        val (south, east, zenith) = toSez(observer, rho)
        return LookAngles(
            azimuthDeg = azimuthFromSez(south, east),
            elevationDeg = asin((zenith / range).coerceIn(-1.0, 1.0)) * RAD2DEG,
            rangeKm = range,
            // The observer is fixed in the Earth frame, so relative velocity = satellite ECEF velocity.
            rangeRateKmPerSec = (rho dot ecef.velocity) / range,
        )
    }

    /** Azimuth/elevation of a direction given as an Earth-fixed vector from the observer. */
    fun lookAnglesEcef(observer: GeodeticLocation, rho: Vector3): Pair<Double, Double> {
        val range = rho.magnitude
        val (south, east, zenith) = toSez(observer, rho)
        return azimuthFromSez(south, east) to asin((zenith / range).coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    /** Rotates an Earth-fixed vector into the observer's South-East-Zenith frame. */
    private fun toSez(observer: GeodeticLocation, rho: Vector3): Triple<Double, Double, Double> {
        val lat = observer.latitudeDeg * DEG2RAD
        val lon = observer.longitudeDeg * DEG2RAD
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)
        val south = sinLat * cosLon * rho.x + sinLat * sinLon * rho.y - cosLat * rho.z
        val east = -sinLon * rho.x + cosLon * rho.y
        val zenith = cosLat * cosLon * rho.x + cosLat * sinLon * rho.y + sinLat * rho.z
        return Triple(south, east, zenith)
    }

    private fun azimuthFromSez(south: Double, east: Double): Double {
        val az = atan2(east, -south) * RAD2DEG
        return if (az < 0.0) az + 360.0 else az
    }
}
