package io.github.kevinnortier.isstracker.sensors

import io.github.kevinnortier.isstracker.orbit.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PointingTest {

    private val eps = 1e-6

    /** Builds Android's row-major R (world = R·device) from the device axes expressed in world coordinates. */
    private fun matrix(x: Vector3, y: Vector3, z: Vector3) = floatArrayOf(
        x.x.toFloat(), y.x.toFloat(), z.x.toFloat(),
        x.y.toFloat(), y.y.toFloat(), z.y.toFloat(),
        x.z.toFloat(), y.z.toFloat(), z.z.toFloat(),
    )

    private val east = Vector3(1.0, 0.0, 0.0)
    private val north = Vector3(0.0, 1.0, 0.0)
    private val up = Vector3(0.0, 0.0, 1.0)

    /** Phone lying flat, screen up, top towards (magnetic) north. */
    private val flatNorth = matrix(east, north, up)

    /** Phone upright in portrait in front of you, screen facing you, back facing (magnetic) north. */
    private val uprightFacingNorth = matrix(east, up, north * -1.0)

    /** Like [uprightFacingNorth] but turned to face (magnetic) east. */
    private val uprightFacingEast = matrix(north * -1.0, up, east * -1.0)

    /** Phone upright facing north, then tilted back by [deg] so the back points [deg] above the horizon. */
    private fun uprightTiltedUp(deg: Double): FloatArray {
        val a = deg * PI / 180.0
        val back = Vector3(0.0, cos(a), sin(a)) // where the back points
        val topAxis = Vector3(0.0, -sin(a), cos(a))
        return matrix(east, topAxis, back * -1.0)
    }

    @Test
    fun `back of an upright phone facing north points north at the horizon`() {
        val p = Pointing.pointing(uprightFacingNorth, PointingAxis.BACK, declinationDeg = 0.0)
        assertEquals(0.0, p.azimuthDeg, eps)
        assertEquals(0.0, p.elevationDeg, eps)
    }

    @Test
    fun `back of an upright phone facing east points east`() {
        val p = Pointing.pointing(uprightFacingEast, PointingAxis.BACK, 0.0)
        assertEquals(90.0, p.azimuthDeg, eps)
        assertEquals(0.0, p.elevationDeg, eps)
    }

    @Test
    fun `top of a flat phone points along the top, back points down`() {
        val top = Pointing.pointing(flatNorth, PointingAxis.TOP, 0.0)
        assertEquals(0.0, top.azimuthDeg, eps)
        assertEquals(0.0, top.elevationDeg, eps)
        assertEquals(-90.0, Pointing.pointing(flatNorth, PointingAxis.BACK, 0.0).elevationDeg, eps)
    }

    @Test
    fun `tilting back raises the elevation`() {
        val p = Pointing.pointing(uprightTiltedUp(30.0), PointingAxis.BACK, 0.0)
        assertEquals(0.0, p.azimuthDeg, 1e-4)
        assertEquals(30.0, p.elevationDeg, 1e-4)
    }

    @Test
    fun `declination converts magnetic to true azimuth`() {
        // With 5° east declination, magnetic north is 5° true.
        val p = Pointing.pointing(uprightFacingNorth, PointingAxis.BACK, declinationDeg = 5.0)
        assertEquals(5.0, p.azimuthDeg, eps)
        // And a target at true azimuth 5° is straight ahead.
        val aim = Pointing.solve(uprightFacingNorth, PointingAxis.BACK, AzEl(5.0, 0.0), declinationDeg = 5.0)
        assertEquals(0.0, aim.separationDeg, 1e-4)
    }

    @Test
    fun `target to the right shows the dot to the right`() {
        val aim = Pointing.solve(uprightFacingNorth, PointingAxis.BACK, AzEl(20.0, 0.0), 0.0)
        assertEquals(20.0, aim.separationDeg, 1e-4)
        assertEquals(20.0, aim.screenX, 1e-4)
        assertEquals(0.0, aim.screenY, 1e-4)
        assertEquals(20.0, aim.deltaAzimuthDeg, 1e-4)
        assertEquals(0.0, aim.deltaElevationDeg, 1e-4)
    }

    @Test
    fun `target above shows the dot up`() {
        val aim = Pointing.solve(uprightFacingNorth, PointingAxis.BACK, AzEl(0.0, 15.0), 0.0)
        assertEquals(15.0, aim.separationDeg, 1e-4)
        assertEquals(0.0, aim.screenX, 1e-4)
        assertEquals(15.0, aim.screenY, 1e-4)
        assertEquals(15.0, aim.deltaElevationDeg, 1e-4)
    }

    @Test
    fun `top mode - target above a flat phone means raise the top`() {
        val aim = Pointing.solve(flatNorth, PointingAxis.TOP, AzEl(0.0, 10.0), 0.0)
        assertEquals(10.0, aim.separationDeg, 1e-4)
        assertEquals(0.0, aim.screenX, 1e-4)
        assertEquals(10.0, aim.screenY, 1e-4)
        val right = Pointing.solve(flatNorth, PointingAxis.TOP, AzEl(10.0, 0.0), 0.0)
        assertEquals(10.0, right.screenX, 1e-4)
    }

    @Test
    fun `dot distance always equals the separation`() {
        for (az in listOf(0.0, 45.0, 170.0, 300.0)) for (el in listOf(-10.0, 0.0, 40.0, 85.0)) {
            val aim = Pointing.solve(uprightTiltedUp(20.0), PointingAxis.BACK, AzEl(az, el), 3.0)
            assertEquals(aim.separationDeg, hypot(aim.screenX, aim.screenY), 1e-3)
        }
    }

    @Test
    fun `aiming works when pointing straight up`() {
        // Phone lying screen-down (e.g. held overhead), so the back points at the zenith.
        val screenDown = matrix(east, north * -1.0, up * -1.0)
        assertEquals(90.0, Pointing.pointing(screenDown, PointingAxis.BACK, 0.0).elevationDeg, eps)
        val aim = Pointing.solve(screenDown, PointingAxis.BACK, AzEl(123.0, 80.0), 0.0)
        assertEquals(10.0, aim.separationDeg, 1e-3) // still well defined at the zenith
    }

    @Test
    fun `angle helpers`() {
        assertEquals(10.0, Pointing.wrap180(370.0), eps)
        assertEquals(-10.0, Pointing.wrap180(350.0), eps)
        assertEquals(359.0, Pointing.normalizeAzimuth(-1.0), eps)
        assertTrue(Pointing.azimuthClose(359.0, 2.0, 5.0))
        assertEquals(90.0, Pointing.separationDeg(AzEl(0.0, 0.0), AzEl(90.0, 0.0)), 1e-6)
        assertEquals(90.0, Pointing.separationDeg(AzEl(0.0, 0.0), AzEl(200.0, 90.0)), 1e-6)
    }

    @Test
    fun `smoothing moves part of the way and keeps a valid rotation`() {
        val half = Pointing.smooth(uprightFacingNorth, uprightFacingEast, 0.5f)
        val p = Pointing.pointing(half, PointingAxis.BACK, 0.0)
        assertEquals(45.0, p.azimuthDeg, 1e-3)
        // Columns stay unit length and perpendicular.
        val c0 = Vector3(half[0].toDouble(), half[3].toDouble(), half[6].toDouble())
        val c1 = Vector3(half[1].toDouble(), half[4].toDouble(), half[7].toDouble())
        assertEquals(1.0, c0.magnitude, 1e-5)
        assertEquals(1.0, c1.magnitude, 1e-5)
        assertEquals(0.0, c0 dot c1, 1e-5)
        // No previous value: just the reading.
        assertTrue(Pointing.smooth(null, flatNorth, 0.1f).contentEquals(flatNorth))
    }
}
