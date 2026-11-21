package io.github.kevinnortier.isstracker.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Compares our SGP4 against python-sgp4 (Vallado's reference implementation). */
class Sgp4Test {

    // Same algorithm in double precision: differences should be far below a millimetre.
    private val positionToleranceKm = 1e-6
    private val velocityToleranceKmPerSec = 1e-9

    @Test
    fun `matches reference implementation for all test cases`() {
        for (case in ReferenceData.tleCases) {
            val sgp4 = Sgp4(TestUtil.tle(case))
            for (expected in case.states) {
                val actual = sgp4.propagate(expected.tsince)
                val label = "${case.name} t=${expected.tsince}"
                assertEquals("$label x", expected.r[0], actual.position.x, positionToleranceKm)
                assertEquals("$label y", expected.r[1], actual.position.y, positionToleranceKm)
                assertEquals("$label z", expected.r[2], actual.position.z, positionToleranceKm)
                assertEquals("$label vx", expected.v[0], actual.velocity.x, velocityToleranceKmPerSec)
                assertEquals("$label vy", expected.v[1], actual.velocity.y, velocityToleranceKmPerSec)
                assertEquals("$label vz", expected.v[2], actual.velocity.z, velocityToleranceKmPerSec)
            }
        }
    }

    @Test
    fun `propagating by instant equals propagating by minutes`() {
        val tle = TestUtil.tle(TestUtil.tleCase("ISS_25544"))
        val sgp4 = Sgp4(tle)
        val byMinutes = sgp4.propagate(90.0)
        val byInstant = sgp4.propagate(tle.epoch.plusSeconds(90 * 60))
        assertEquals(byMinutes.position.x, byInstant.position.x, 1e-9)
        assertEquals(byMinutes.position.y, byInstant.position.y, 1e-9)
        assertEquals(byMinutes.position.z, byInstant.position.z, 1e-9)
    }

    @Test
    fun `iss altitude and speed are plausible`() {
        val sgp4 = Sgp4(TestUtil.tle(TestUtil.tleCase("ISS_25544")))
        for (minutes in 0..1440 step 10) {
            val state = sgp4.propagate(minutes.toDouble())
            val altitudeKm = state.position.magnitude - 6378.0
            assertEquals("altitude at $minutes min", 415.0, altitudeKm, 35.0)
            assertEquals("speed at $minutes min", 7.66, state.velocity.magnitude, 0.05)
        }
    }

    @Test
    fun `deep space orbits are rejected`() {
        // GPS-like orbit: 2 revolutions per day (period 720 min)
        val l1 = TestUtil.withChecksum("1 99997U 00000A   26325.50000000  .00000000  00000-0  00000-0 0  9990")
        val l2 = TestUtil.withChecksum("2 99997  55.0000 100.0000 0050000  90.0000 270.0000  2.00560000    10")
        val tle = TleParser.parse(null, l1, l2)
        assertThrows(DeepSpaceNotSupportedException::class.java) { Sgp4(tle) }
    }
}
