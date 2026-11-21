package io.github.kevinnortier.isstracker.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Compares pass predictions against Skyfield's find_events (ISS, Greenwich and Sydney, 0° and 10°). */
class PassPredictorTest {

    // Skyfield uses UT1 and its own root finder; agreement within a second is expected.
    private val timeToleranceMillis = 1_000L
    private val apexToleranceDeg = 0.01
    private val azimuthToleranceDeg = 0.05

    private fun predictor(case: ReferenceData.PassCase) = PassPredictor(
        TestUtil.tle(TestUtil.tleCase(case.tle)),
        GeodeticLocation(case.latitudeDeg, case.longitudeDeg, case.altitudeMeters),
        case.minElevationDeg,
    )

    private fun assertTimeClose(label: String, expectedMillis: Long, actual: Instant) {
        val diff = abs(actual.toEpochMilli() - expectedMillis)
        assertTrue("$label off by $diff ms", diff <= timeToleranceMillis)
    }

    @Test
    fun `passes match Skyfield`() {
        for (case in ReferenceData.passCases) {
            val from = Instant.ofEpochMilli(case.fromMillis)
            // Only compare passes that rise inside the window, like the reference.
            val passes = predictor(case).passes(from, Instant.ofEpochMilli(case.untilMillis))
                .filter { !it.rise.time.isBefore(from) }
            val label = "lat=${case.latitudeDeg} min=${case.minElevationDeg}"
            // The reference only contains passes that also set inside the window.
            val comparable = passes.filter { it.set.time.toEpochMilli() <= case.untilMillis }
            assertEquals("$label number of passes", case.passes.size, comparable.size)

            for ((ref, pass) in case.passes.zip(comparable)) {
                val p = "$label rise=${pass.rise.time}"
                assertTimeClose("$p rise", ref.riseMillis, pass.rise.time)
                assertTimeClose("$p apex", ref.apexMillis, pass.apex.time)
                assertTimeClose("$p set", ref.setMillis, pass.set.time)
                assertEquals("$p apex elevation", ref.apexElevationDeg, pass.apex.elevationDeg, apexToleranceDeg)
                assertEquals("$p rise azimuth", ref.riseAzimuthDeg, pass.rise.azimuthDeg, azimuthToleranceDeg)
                assertEquals("$p set azimuth", ref.setAzimuthDeg, pass.set.azimuthDeg, azimuthToleranceDeg)
                assertEquals("$p visible", ref.visible, pass.visible)
            }
        }
    }

    @Test
    fun `rise and set are at the minimum elevation`() {
        for (case in ReferenceData.passCases) {
            val passes = predictor(case).passes(Instant.ofEpochMilli(case.fromMillis), Instant.ofEpochMilli(case.untilMillis))
            for (pass in passes) {
                assertEquals(case.minElevationDeg, pass.rise.elevationDeg, 0.01)
                assertEquals(case.minElevationDeg, pass.set.elevationDeg, 0.01)
                assertTrue(pass.apex.elevationDeg >= case.minElevationDeg)
                assertTrue(pass.rise.time < pass.apex.time && pass.apex.time < pass.set.time)
            }
        }
    }

    @Test
    fun `passes are in order and do not overlap`() {
        val case = ReferenceData.passCases.first()
        val passes = predictor(case).passes(Instant.ofEpochMilli(case.fromMillis), Instant.ofEpochMilli(case.untilMillis))
        for ((a, b) in passes.zipWithNext()) assertTrue(a.set.time < b.rise.time)
    }

    @Test
    fun `starting in the middle of a pass gives its real rise time`() {
        val case = ReferenceData.passCases.first()
        val ref = case.passes[1]
        val middle = Instant.ofEpochMilli(ref.apexMillis)
        val first = predictor(case).passes(middle, middle.plus(Duration.ofHours(1))).first()
        assertTimeClose("rise", ref.riseMillis, first.rise.time)
        assertTimeClose("set", ref.setMillis, first.set.time)
        assertTrue(first.isInProgress(middle))
    }

    @Test
    fun `a pass rising just before the window end is completed`() {
        val case = ReferenceData.passCases.first()
        val ref = case.passes[2]
        val until = Instant.ofEpochMilli(ref.riseMillis + 60_000)
        val last = predictor(case).passes(Instant.ofEpochMilli(case.fromMillis), until).last()
        assertTimeClose("set", ref.setMillis, last.set.time)
    }

    @Test
    fun `higher minimum elevation gives fewer and shorter passes`() {
        val low = ReferenceData.passCases.first { it.latitudeDeg > 0 && it.minElevationDeg == 0.0 }
        val high = ReferenceData.passCases.first { it.latitudeDeg > 0 && it.minElevationDeg == 10.0 }
        assertTrue(high.passes.size < low.passes.size)
    }

    @Test
    fun `visible window lies inside the pass`() {
        for (case in ReferenceData.passCases) {
            val passes = predictor(case).passes(Instant.ofEpochMilli(case.fromMillis), Instant.ofEpochMilli(case.untilMillis))
            for (pass in passes.filter { it.visible }) {
                assertTrue(!pass.visibleFrom!!.isBefore(pass.rise.time))
                assertTrue(!pass.visibleUntil!!.isAfter(pass.set.time))
                assertTrue(!pass.visibleFrom.isAfter(pass.visibleUntil))
            }
        }
    }

    @Test
    fun `ten days of predictions is fast enough`() {
        val case = ReferenceData.passCases.first()
        val from = Instant.ofEpochMilli(case.fromMillis)
        val p = predictor(case)
        p.passes(from, from.plus(Duration.ofDays(1))) // warm up the JIT
        val start = System.nanoTime()
        val passes = p.passes(from, from.plus(Duration.ofDays(10)))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("found ${passes.size} passes", passes.size in 40..90)
        assertTrue("took $millis ms", millis < 2_000)
        println("10 days: ${passes.size} passes in $millis ms")
    }
}
