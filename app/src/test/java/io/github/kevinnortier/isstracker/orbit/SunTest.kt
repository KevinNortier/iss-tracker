package io.github.kevinnortier.isstracker.orbit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SunTest {

    @Test
    fun `sun position matches Skyfield over a year`() {
        val observer = GeodeticLocation(
            ReferenceData.sunObserverLatitudeDeg,
            ReferenceData.sunObserverLongitudeDeg,
            ReferenceData.sunObserverAltitudeMeters,
        )
        for (s in ReferenceData.sunSamples) {
            val time = Instant.ofEpochMilli(s.epochMillis)
            val (az, el) = Sun.lookAngles(observer, time)
            val separation = TestUtil.angularSeparationDeg(az, el, s.azimuthDeg, s.elevationDeg)
            // The low-precision formula is good to ~0.01° (measured worst case 0.0101°).
            assertTrue("$time off by $separation°", separation < 0.02)
        }
    }

    @Test
    fun `satellite on day side is sunlit`() {
        val time = Instant.parse("2026-06-21T12:00:00Z")
        val sunDir = Sun.positionTeme(time).normalized()
        assertTrue(Sun.isSunlit(sunDir * 6800.0, time))
    }

    @Test
    fun `satellite directly behind earth is in shadow`() {
        val time = Instant.parse("2026-06-21T12:00:00Z")
        val sunDir = Sun.positionTeme(time).normalized()
        assertFalse(Sun.isSunlit(sunDir * -6800.0, time))
    }

    @Test
    fun `satellite on night side but outside shadow cylinder is sunlit`() {
        val time = Instant.parse("2026-06-21T12:00:00Z")
        val sunDir = Sun.positionTeme(time).normalized()
        // Perpendicular to the sun direction, then slightly behind the Earth.
        val perpendicular = (sunDir cross Vector3(0.0, 0.0, 1.0)).normalized()
        assertTrue(Sun.isSunlit(perpendicular * 6800.0 - sunDir * 100.0, time))
    }
}
