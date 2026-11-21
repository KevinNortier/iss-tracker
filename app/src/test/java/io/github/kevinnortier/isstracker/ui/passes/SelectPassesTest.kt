package io.github.kevinnortier.isstracker.ui.passes

import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.SkyPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SelectPassesTest {

    private fun t(minute: Long): Instant = Instant.parse("2026-11-22T18:00:00Z").plusSeconds(minute * 60)

    private fun pass(riseMinute: Long, setMinute: Long) = Pass(
        rise = SkyPoint(t(riseMinute), 200.0, 0.0),
        apex = SkyPoint(t((riseMinute + setMinute) / 2), 150.0, 30.0),
        set = SkyPoint(t(setMinute), 100.0, 0.0),
        visible = false,
        visibleFrom = null,
        visibleUntil = null,
    )

    private val a = pass(0, 10)
    private val b = pass(90, 100)
    private val c = pass(180, 190)
    private val all = listOf(a, b, c)

    @Test
    fun `before any pass`() {
        val s = selectPasses(all, t(-5))
        assertEquals(all, s.upcoming)
        assertNull(s.current)
        assertEquals(a, s.next)
    }

    @Test
    fun `during a pass it is current and the following one is next`() {
        val s = selectPasses(all, t(5))
        assertEquals(all, s.upcoming)
        assertEquals(a, s.current)
        assertEquals(b, s.next)
    }

    @Test
    fun `at the exact rise time the pass is in progress`() {
        val s = selectPasses(all, t(90))
        assertEquals(b, s.current)
        assertEquals(c, s.next)
    }

    @Test
    fun `ended passes drop out`() {
        val s = selectPasses(all, t(10))
        assertEquals(listOf(b, c), s.upcoming)
        assertNull(s.current)
        assertEquals(b, s.next)
    }

    @Test
    fun `after the last pass`() {
        val s = selectPasses(all, t(200))
        assertEquals(emptyList<Pass>(), s.upcoming)
        assertNull(s.current)
        assertNull(s.next)
    }
}
