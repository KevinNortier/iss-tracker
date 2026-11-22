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

    /** True if the difference between two angles is below [toleranceDeg] (handles 359° vs 1°). */
    fun azimuthClose(a: Double, b: Double, toleranceDeg: Double): Boolean = abs(wrap180(a - b)) <= toleranceDeg
}
