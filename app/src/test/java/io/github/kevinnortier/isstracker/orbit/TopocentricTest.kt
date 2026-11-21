package io.github.kevinnortier.isstracker.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Compares look angles and the sunlit flag against Skyfield. */
class TopocentricTest {

    // Skyfield uses UT1 and a full precession/nutation model; we use UTC and GMST.
    // The (finite-difference) range rate from Skyfield is only accurate to ~1 m/s.
    // Measured worst case: 0.0012°, 33 m, 0.05 m/s.
    private val directionToleranceDeg = 0.01
    private val rangeToleranceKm = 0.1
    private val rangeRateToleranceKmPerSec = 0.001

    @Test
    fun `look angles match Skyfield`() {
        for (case in ReferenceData.lookCases) {
            val observer = GeodeticLocation(case.latitudeDeg, case.longitudeDeg, case.altitudeMeters)
            val sgp4 = Sgp4(TestUtil.tle(TestUtil.tleCase(case.tle)))
            for (s in case.samples) {
                val time = Instant.ofEpochMilli(s.epochMillis)
                val look = Topocentric.lookAngles(observer, sgp4.propagate(time))
                val label = "lat=${case.latitudeDeg} t=$time"
                val separation = TestUtil.angularSeparationDeg(
                    look.azimuthDeg, look.elevationDeg, s.azimuthDeg, s.elevationDeg,
                )
                assertTrue("$label direction off by $separation°", separation < directionToleranceDeg)
                assertEquals("$label elevation", s.elevationDeg, look.elevationDeg, directionToleranceDeg)
                assertEquals("$label range", s.rangeKm, look.rangeKm, rangeToleranceKm)
                assertEquals("$label range rate", s.rangeRateKmPerSec, look.rangeRateKmPerSec, rangeRateToleranceKmPerSec)
            }
        }
    }

    @Test
    fun `sunlit flag matches Skyfield`() {
        // Our cylindrical shadow and Skyfield's spherical-Earth test only differ in the
        // few seconds when the ISS crosses the shadow edge.
        for (case in ReferenceData.lookCases) {
            val sgp4 = Sgp4(TestUtil.tle(TestUtil.tleCase(case.tle)))
            val mismatches = case.samples.count { s ->
                val time = Instant.ofEpochMilli(s.epochMillis)
                Sun.isSunlit(sgp4.propagate(time).position, time) != s.sunlit
            }
            assertTrue("$mismatches sunlit mismatches", mismatches <= 1)
        }
    }

    @Test
    fun `azimuth is in range 0 to 360`() {
        val case = ReferenceData.lookCases.first()
        val observer = GeodeticLocation(case.latitudeDeg, case.longitudeDeg, case.altitudeMeters)
        val tle = TestUtil.tle(TestUtil.tleCase(case.tle))
        val sgp4 = Sgp4(tle)
        for (minutes in 0..1440 step 3) {
            val look = Topocentric.lookAngles(observer, sgp4.propagate(minutes.toDouble()))
            assertTrue(look.azimuthDeg >= 0.0 && look.azimuthDeg < 360.0)
            assertTrue(look.elevationDeg >= -90.0 && look.elevationDeg <= 90.0)
        }
    }

    @Test
    fun `observer ecef is on the WGS84 ellipsoid`() {
        assertEquals(6378.137, GeodeticLocation(0.0, 0.0).ecef.x, 1e-9)
        assertEquals(6356.752314245, GeodeticLocation(90.0, 0.0).ecef.z, 1e-6)
        assertEquals(6379.137, GeodeticLocation(0.0, 0.0, 1000.0).ecef.x, 1e-9)
    }
}
