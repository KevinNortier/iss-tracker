package io.github.kevinnortier.isstracker.ui.passdetails

import org.junit.Assert.assertEquals
import org.junit.Test

class SkyPlotTest {

    private fun assertPos(expectedX: Double, expectedY: Double, az: Double, el: Double) {
        val (x, y) = SkyPlot.position(az, el)
        assertEquals("x for az=$az el=$el", expectedX, x, 1e-9)
        assertEquals("y for az=$az el=$el", expectedY, y, 1e-9)
    }

    @Test
    fun `zenith is the centre`() = assertPos(0.0, 0.0, az = 123.0, el = 90.0)

    @Test
    fun `horizon points are on the edge, north up and east right`() {
        assertPos(0.0, -1.0, az = 0.0, el = 0.0)   // north: top
        assertPos(1.0, 0.0, az = 90.0, el = 0.0)   // east: right
        assertPos(0.0, 1.0, az = 180.0, el = 0.0)  // south: bottom
        assertPos(-1.0, 0.0, az = 270.0, el = 0.0) // west: left
    }

    @Test
    fun `elevation maps linearly to the radius`() {
        assertPos(0.0, -2.0 / 3.0, az = 0.0, el = 30.0)
        assertPos(1.0 / 3.0, 0.0, az = 90.0, el = 60.0)
    }
}
