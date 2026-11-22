package io.github.kevinnortier.isstracker.sensors

import io.github.kevinnortier.isstracker.orbit.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Which side of the phone points at the satellite. */
enum class PointingAxis {
    /** The back of the phone (camera side), phone held like a camera: device −Z axis. */
    BACK,

    /** The top edge of the phone, e.g. phone strapped along an antenna boom: device +Y axis. */
    TOP,
}

/** Where the phone's heading (which way is north) comes from. */
enum class HeadingSource {
    /** Rotation vector sensor: gyro + accelerometer + magnetometer. Absolute, but disturbed by magnets and metal. */
    COMPASS,

    /** Game rotation vector: gyro + accelerometer only. Immune to magnets; the heading must be aligned once. */
    GYRO,
}

/** A direction as azimuth (degrees from north, clockwise) and elevation (degrees above the horizon). */
data class AzEl(val azimuthDeg: Double, val elevationDeg: Double)

/**
 * Where the target is relative to where the phone points.
 *
 * [screenX]/[screenY] give the direction to move the phone, as seen on the screen (right/up are
 * positive), scaled so their length equals [separationDeg]. The aiming ring draws the target
 * dot there; the centre means "on target".
 */
data class AimSolution(
    val pointing: AzEl,
    val separationDeg: Double,
    val screenX: Double,
    val screenY: Double,
    /** How far to turn right (negative: left), −180..180. */
    val deltaAzimuthDeg: Double,
    /** How far to tilt up (negative: down). */
    val deltaElevationDeg: Double,
)

/**
 * Orientation math for aiming. The rotation matrix is Android's (from
 * `SensorManager.getRotationMatrixFromVector`): row-major 3×3, world = R · device, with world
 * axes X = east, Y = magnetic north, Z = up. Azimuths in and out of these functions are
 * relative to **true** north; [declinationDeg] (east positive) converts between the two.
 */
object Pointing {

    private const val RAD2DEG = 180.0 / PI
    private const val DEG2RAD = PI / 180.0

    /** The phone's pointing axis in its own coordinates. */
    fun deviceAxis(axis: PointingAxis): Vector3 = when (axis) {
        PointingAxis.BACK -> Vector3(0.0, 0.0, -1.0)
        PointingAxis.TOP -> Vector3(0.0, 1.0, 0.0)
    }

    /** device → world (east, magnetic north, up). */
    fun toWorld(r: FloatArray, device: Vector3): Vector3 = Vector3(
        r[0] * device.x + r[1] * device.y + r[2] * device.z,
        r[3] * device.x + r[4] * device.y + r[5] * device.z,
        r[6] * device.x + r[7] * device.y + r[8] * device.z,
    )

    /** world (east, magnetic north, up) → device (the transpose, since R is a rotation). */
    fun toDevice(r: FloatArray, world: Vector3): Vector3 = Vector3(
        r[0] * world.x + r[3] * world.y + r[6] * world.z,
        r[1] * world.x + r[4] * world.y + r[7] * world.z,
        r[2] * world.x + r[5] * world.y + r[8] * world.z,
    )

    /** Az/el (true north) of a world vector given in east / magnetic-north / up. */
    fun azElOf(world: Vector3, declinationDeg: Double): AzEl {
        val v = world.normalized()
        val magneticAz = atan2(v.x, v.y) * RAD2DEG
        return AzEl(
            azimuthDeg = normalizeAzimuth(magneticAz + declinationDeg),
            elevationDeg = asin(v.z.coerceIn(-1.0, 1.0)) * RAD2DEG,
        )
    }

    /** Unit world vector (east, magnetic north, up) for an az/el relative to true north. */
    fun worldVector(target: AzEl, declinationDeg: Double): Vector3 {
        val az = (target.azimuthDeg - declinationDeg) * DEG2RAD
        val el = target.elevationDeg * DEG2RAD
        return Vector3(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    /** Where the phone's [axis] points (true north). */
    fun pointing(r: FloatArray, axis: PointingAxis, declinationDeg: Double): AzEl =
        azElOf(toWorld(r, deviceAxis(axis)), declinationDeg)

    /** Everything the aiming screen needs to guide the user from the current pointing to [target]. */
    fun solve(r: FloatArray, axis: PointingAxis, target: AzEl, declinationDeg: Double): AimSolution {
        val pointing = pointing(r, axis, declinationDeg)
        val t = toDevice(r, worldVector(target, declinationDeg))
        val along = when (axis) {
            PointingAxis.BACK -> -t.z
            PointingAxis.TOP -> t.y
        }
        // Components across the pointing axis, as the user sees them on the screen.
        // BACK: screen right = +X, screen up = +Y.
        // TOP: screen right = +X; "up" on the screen = towards the screen's face (+Z), i.e. raise the top.
        val (across1, across2) = when (axis) {
            PointingAxis.BACK -> t.x to t.y
            PointingAxis.TOP -> t.x to t.z
        }
        val separation = acos(along.coerceIn(-1.0, 1.0)) * RAD2DEG
        val acrossLength = hypot(across1, across2)
        val (sx, sy) = if (acrossLength < 1e-9) {
            0.0 to 0.0
        } else {
            across1 / acrossLength * separation to across2 / acrossLength * separation
        }
        return AimSolution(
            pointing = pointing,
            separationDeg = separation,
            screenX = sx,
            screenY = sy,
            deltaAzimuthDeg = wrap180(target.azimuthDeg - pointing.azimuthDeg),
            deltaElevationDeg = target.elevationDeg - pointing.elevationDeg,
        )
    }

    /** Angle between two directions, in degrees. */
    fun separationDeg(a: AzEl, b: AzEl): Double {
        val va = worldVector(a, 0.0)
        val vb = worldVector(b, 0.0)
        return acos((va dot vb).coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    fun normalizeAzimuth(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Wraps an angle difference into −180..180. */
    fun wrap180(deg: Double): Double {
        val a = normalizeAzimuth(deg)
        return if (a > 180.0) a - 360.0 else a
    }

    /**
     * Exponential smoothing of a rotation matrix, re-orthonormalised so it stays a valid rotation.
     * [alpha] = how much of the new reading to take (0..1). Returns a new array.
     */
    fun smooth(previous: FloatArray?, current: FloatArray, alpha: Float): FloatArray {
        if (previous == null) return current.copyOf()
        val m = FloatArray(9) { previous[it] + alpha * (current[it] - previous[it]) }
        return orthonormalize(m)
    }

    /** Gram–Schmidt on the columns (device axes in world coordinates). */
    fun orthonormalize(m: FloatArray): FloatArray {
        val c0 = Vector3(m[0].toDouble(), m[3].toDouble(), m[6].toDouble()).normalized()
        var c1 = Vector3(m[1].toDouble(), m[4].toDouble(), m[7].toDouble())
        c1 = (c1 - c0 * (c0 dot c1)).normalized()
        val c2 = c0 cross c1
        return floatArrayOf(
            c0.x.toFloat(), c1.x.toFloat(), c2.x.toFloat(),
            c0.y.toFloat(), c1.y.toFloat(), c2.y.toFloat(),
            c0.z.toFloat(), c1.z.toFloat(), c2.z.toFloat(),
        )
    }

    // ---- Gyro heading alignment -------------------------------------------------------------
    //
    // The game rotation vector has a correct "up" (from gravity) but an arbitrary "north". It differs
    // from the real world frame only by a rotation about the vertical axis, so one angle (the
    // heading offset) is enough to align it: true azimuth = gyro azimuth + offset.

    /**
     * Rotates the world frame of [r] clockwise (seen from above) about the vertical by [degrees], so
     * every azimuth computed from the result is [degrees] larger. Returns a new matrix.
     */
    fun rotateAboutVertical(r: FloatArray, degrees: Double): FloatArray {
        val a = degrees * DEG2RAD
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        // world' = Rcw · world, with Rcw = [[c, s, 0], [-s, c, 0], [0, 0, 1]]
        return floatArrayOf(
            c * r[0] + s * r[3], c * r[1] + s * r[4], c * r[2] + s * r[5],
            -s * r[0] + c * r[3], -s * r[1] + c * r[4], -s * r[2] + c * r[5],
            r[6], r[7], r[8],
        )
    }

    /**
     * Azimuth of a device axis projected onto the horizontal plane, measured from the frame's own
     * north, or null when the axis is within about 10° of vertical (its azimuth is then meaningless).
     */
    fun horizontalAzimuth(r: FloatArray, deviceAxis: Vector3): Double? {
        val w = toWorld(r, deviceAxis)
        if (hypot(w.x, w.y) < 0.17) return null
        return normalizeAzimuth(atan2(w.x, w.y) * RAD2DEG)
    }

    /** The device axis (X, Y or Z) that is closest to horizontal, i.e. best for comparing headings. */
    fun mostHorizontalAxis(r: FloatArray): Vector3 = listOf(
        Vector3(1.0, 0.0, 0.0),
        Vector3(0.0, 1.0, 0.0),
        Vector3(0.0, 0.0, 1.0),
    ).maxBy { val w = toWorld(r, it); hypot(w.x, w.y) }

    /**
     * Heading offset for gyro mode from a compass (rotation vector) and a gyro (game rotation
     * vector) matrix taken at the same moment. Works in any phone orientation.
     */
    fun gyroOffsetFromCompass(compass: FloatArray, gyro: FloatArray, declinationDeg: Double): Double {
        val axis = mostHorizontalAxis(gyro)
        val compassAz = horizontalAzimuth(compass, axis) ?: 0.0
        val gyroAz = horizontalAzimuth(gyro, axis) ?: 0.0
        return wrap180(compassAz + declinationDeg - gyroAz)
    }

    /**
     * Heading offset for gyro mode when the phone's [axis] is pointed at something with a known true
     * azimuth (the sun, the ISS). Null when the pointing axis is too close to vertical to tell.
     */
    fun gyroOffsetFromTarget(gyro: FloatArray, axis: PointingAxis, targetAzimuthDeg: Double): Double? {
        val pointingAz = horizontalAzimuth(gyro, deviceAxis(axis)) ?: return null
        return wrap180(targetAzimuthDeg - pointingAz)
    }

    /** True if the difference between two angles is below [toleranceDeg] (handles 359° vs 1°). */
    fun azimuthClose(a: Double, b: Double, toleranceDeg: Double): Boolean = abs(wrap180(a - b)) <= toleranceDeg
}
