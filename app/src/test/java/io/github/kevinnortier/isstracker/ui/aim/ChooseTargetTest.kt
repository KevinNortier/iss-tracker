package io.github.kevinnortier.isstracker.ui.aim

import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.SkyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChooseTargetTest {

    private val base = Instant.parse("2026-11-22T18:00:00Z")
    private fun t(minute: Long): Instant = base.plusSeconds(minute * 60)

    private fun pass(riseMinute: Long) = Pass(
        rise = SkyPoint(t(riseMinute), 212.0, 0.0),
        apex = SkyPoint(t(riseMinute + 5), 160.0, 40.0),
        set = SkyPoint(t(riseMinute + 10), 110.0, 0.0),
        visible = false,
        visibleFrom = null,
        visibleUntil = null,
    )

    private fun look(az: Double, el: Double) = LookAngles(az, el, 1000.0, -2.0)

    @Test
    fun `above the horizon aims at the satellite`() {
        val p = pass(0)
        val target = chooseTarget(look(180.0, 25.0), listOf(p), t(3))
        assertTrue(target is AimTarget.Live)
        target as AimTarget.Live
        assertEquals(180.0, target.direction.azimuthDeg, 0.0)
        assertEquals(25.0, target.direction.elevationDeg, 0.0)
        assertEquals(p, target.pass)
    }

    @Test
    fun `below the horizon aims at the next rise point`() {
        val next = pass(90)
        val target = chooseTarget(look(10.0, -30.0), listOf(pass(0), next), t(20))
        assertTrue(target is AimTarget.NextRise)
        target as AimTarget.NextRise
        assertEquals(next, target.pass)
        assertEquals(212.0, target.direction.azimuthDeg, 0.0)
        assertEquals(0.0, target.direction.elevationDeg, 0.0)
    }

    @Test
    fun `just above the horizon outside a counted pass still aims at the satellite`() {
        // e.g. min elevation 10°: the satellite is up at 3° but not "in a pass" yet
        val target = chooseTarget(look(200.0, 3.0), listOf(pass(90)), t(0))
        assertTrue(target is AimTarget.Live)
    }

    @Test
    fun `nothing to aim at without passes while below the horizon`() {
        assertNull(chooseTarget(look(0.0, -40.0), emptyList(), t(0)))
    }
}
