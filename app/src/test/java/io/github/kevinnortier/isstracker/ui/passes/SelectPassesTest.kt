package io.github.kevinnortier.isstracker.ui.passes

import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.SkyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SelectPassesTest {

    private fun t(minute: Long): Instant = Instant.parse("2026-11-22T18:00:00Z").plusSeconds(minute * 60)

    private fun pass(riseMinute: Long, setMinute: Long, visible: Boolean = false) = Pass(
        rise = SkyPoint(t(riseMinute), 200.0, 0.0),
        apex = SkyPoint(t((riseMinute + setMinute) / 2), 150.0, 30.0),
        set = SkyPoint(t(setMinute), 100.0, 0.0),
        visible = visible,
        visibleFrom = if (visible) t(riseMinute) else null,
        visibleUntil = if (visible) t(setMinute) else null,
    )

    private val iss = Satellite.ISS
    private val css = Satellite(48274, "CSS (TIANHE)", bright = true)
    private val faint = Satellite(7530, "AO-7")

    private val a = SatPass(iss, pass(0, 10))
    private val b = SatPass(iss, pass(90, 100))
    private val c = SatPass(iss, pass(180, 190))
    private val all = listOf(a, b, c)

    @Test
    fun `before any pass`() {
        val s = selectPasses(all, t(-5))
        assertEquals(all, s.upcoming)
        assertEquals(emptyList<SatPass>(), s.current)
        assertEquals(a, s.next)
    }

    @Test
    fun `during a pass it is current and the following one is next`() {
        val s = selectPasses(all, t(5))
        assertEquals(all, s.upcoming)
        assertEquals(listOf(a), s.current)
        assertEquals(b, s.next)
    }

    @Test
    fun `at the exact rise time the pass is in progress`() {
        val s = selectPasses(all, t(90))
        assertEquals(listOf(b), s.current)
        assertEquals(c, s.next)
    }

    @Test
    fun `ended passes drop out`() {
        val s = selectPasses(all, t(10))
        assertEquals(listOf(b, c), s.upcoming)
        assertEquals(emptyList<SatPass>(), s.current)
        assertEquals(b, s.next)
    }

    @Test
    fun `after the last pass`() {
        val s = selectPasses(all, t(200))
        assertEquals(emptyList<SatPass>(), s.upcoming)
        assertEquals(emptyList<SatPass>(), s.current)
        assertNull(s.next)
    }

    @Test
    fun `several satellites can pass at once`() {
        val other = SatPass(css, pass(3, 12))
        val passes = mergePasses(listOf(iss to listOf(a.pass, b.pass), css to listOf(other.pass)))
        val s = selectPasses(passes, t(5))
        assertEquals(listOf(a, other), s.current)
        assertEquals(b, s.next)
    }

    @Test
    fun `merged passes are in rise time order`() {
        val merged = mergePasses(
            listOf(
                iss to listOf(pass(0, 10), pass(90, 100)),
                css to listOf(pass(40, 50), pass(200, 210)),
            ),
        )
        assertEquals(listOf(0L, 40L, 90L, 200L), merged.map { (it.pass.rise.time.epochSecond - t(0).epochSecond) / 60 })
        assertEquals(listOf(iss, css, iss, css), merged.map { it.satellite })
    }

    @Test
    fun `only bright satellites are visible to the eye`() {
        val bright = SatPass(iss, pass(0, 10, visible = true))
        val dim = SatPass(faint, pass(0, 10, visible = true))
        assertTrue(bright.visibleToEye)
        assertFalse(bright.sunlitOnly)
        assertFalse(dim.visibleToEye)
        assertTrue(dim.sunlitOnly)
        assertFalse(SatPass(faint, pass(0, 10)).sunlitOnly)
    }

    @Test
    fun `an empty or stale filter shows every tracked satellite`() {
        val tracked = listOf(iss, css, faint)
        val everything = setOf(iss.noradId, css.noradId, faint.noradId)
        assertEquals(everything, effectiveFilter(emptySet(), tracked))
        assertEquals(everything, effectiveFilter(setOf(99999), tracked))
        assertEquals(setOf(css.noradId), effectiveFilter(setOf(css.noradId, 99999), tracked))
    }

    @Test
    fun `title names the satellite only when there is one`() {
        assertEquals("ISS passes", passesTitle(listOf(iss)))
        assertEquals("Passes", passesTitle(listOf(iss, css)))
        assertEquals("Passes", passesTitle(emptyList()))
    }
}
