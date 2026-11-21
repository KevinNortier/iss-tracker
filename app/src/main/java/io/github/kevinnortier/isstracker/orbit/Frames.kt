package io.github.kevinnortier.isstracker.orbit

import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Time scales and coordinate frame conversions. */
object Frames {

    private const val TWO_PI = 2.0 * PI
    private const val DEG2RAD = PI / 180.0

    /** Earth's rotation rate (rad/s). */
    const val EARTH_ROTATION_RATE = 7.292115146706979e-5

    /** Julian date (UTC) of an instant. UT1 is approximated by UTC (difference < 0.9 s). */
    fun julianDate(time: Instant): Double =
        2440587.5 + (time.epochSecond + time.nano / 1e9) / 86400.0

    /** Greenwich Mean Sidereal Time (IAU-82, as used with SGP4), in radians [0, 2π). */
    fun gmst(time: Instant): Double {
        val tut1 = (julianDate(time) - 2451545.0) / 36525.0
        val seconds = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 +
            (876600.0 * 3600.0 + 8640184.812866) * tut1 + 67310.54841
        // 360 deg / 86400 s = 1/240 deg per second
        val gmst = (seconds * DEG2RAD / 240.0).rem(TWO_PI)
        return if (gmst < 0.0) gmst + TWO_PI else gmst
    }

    /**
     * Converts a TEME state vector to the Earth-fixed frame (ECEF, ignoring polar motion,
     * which is only a few metres). The velocity is relative to the rotating Earth.
     */
    fun temeToEcef(state: StateVector): StateVector {
        val g = gmst(state.time)
        val c = cos(g)
        val s = sin(g)
        val r = state.position
        val v = state.velocity
        val rEcef = Vector3(c * r.x + s * r.y, -s * r.x + c * r.y, r.z)
        val vRot = Vector3(c * v.x + s * v.y, -s * v.x + c * v.y, v.z)
        // Subtract ω × r to get velocity relative to the rotating Earth.
        val vEcef = Vector3(
            vRot.x + EARTH_ROTATION_RATE * rEcef.y,
            vRot.y - EARTH_ROTATION_RATE * rEcef.x,
            vRot.z,
        )
        return StateVector(state.time, rEcef, vEcef)
    }

    /** Rotates a TEME position into the Earth-fixed frame (no velocity). */
    fun temeToEcef(position: Vector3, time: Instant): Vector3 {
        val g = gmst(time)
        val c = cos(g)
        val s = sin(g)
        return Vector3(c * position.x + s * position.y, -s * position.x + c * position.y, position.z)
    }
}
