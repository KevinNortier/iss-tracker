package io.github.kevinnortier.isstracker.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class TleParserTest {

    private val issText = """
        ISS (ZARYA)
        1 25544U 98067A   26325.59122794  .00010184  00000+0  18913-3 0  9995
        2 25544  51.6319 241.1476 0007381  50.6945 309.4697 15.49534382596158
    """.trimIndent()

    @Test
    fun `parses a three-line TLE`() {
        val tle = TleParser.parseAll(issText).single()
        assertEquals("ISS (ZARYA)", tle.name)
        assertEquals(25544, tle.noradId)
        assertEquals(0.00010184, tle.meanMotionDot, 1e-15)
        assertEquals(0.0, tle.meanMotionDdot, 0.0)
        assertEquals(0.18913e-3, tle.bstar, 1e-15)
        assertEquals(51.6319, tle.inclinationDeg, 1e-12)
        assertEquals(241.1476, tle.raanDeg, 1e-12)
        assertEquals(0.0007381, tle.eccentricity, 1e-15)
        assertEquals(50.6945, tle.argOfPerigeeDeg, 1e-12)
        assertEquals(309.4697, tle.meanAnomalyDeg, 1e-12)
        assertEquals(15.49534382, tle.meanMotion, 1e-12)
    }

    @Test
    fun `parses epoch as UTC instant`() {
        val tle = TleParser.parseAll(issText).single()
        // Day 325.59122794 of 2026 = 21 Nov 2026 14:11:22.094 UTC
        val expected = Instant.parse("2026-11-21T00:00:00Z").plusNanos((0.59122794 * 86_400e9).toLong())
        assertEquals(expected.toEpochMilli().toDouble(), tle.epoch.toEpochMilli().toDouble(), 1.0)
    }

    @Test
    fun `epoch matches reference Julian dates`() {
        for (case in ReferenceData.tleCases) {
            val tle = TestUtil.tle(case)
            // 1e-8 days ≈ 1 ms (the TLE itself has 8 decimals of a day)
            assertEquals(case.name, case.epochJulianDate, Frames.julianDate(tle.epoch), 1e-8)
        }
    }

    @Test
    fun `two-digit years before 57 are 2000s`() {
        assertEquals(2000, TestUtil.tle(TestUtil.tleCase("VANGUARD_00005")).epoch.atZone(java.time.ZoneOffset.UTC).year)
        assertEquals(1980, TestUtil.tle(TestUtil.tleCase("STR3_88888")).epoch.atZone(java.time.ZoneOffset.UTC).year)
    }

    @Test
    fun `parses implied decimal with exponent`() {
        val case = TestUtil.tleCase("STR3_88888")
        val tle = TestUtil.tle(case)
        assertEquals(0.13844e-3, tle.meanMotionDdot, 1e-15)
        assertEquals(0.66816e-4, tle.bstar, 1e-15)
    }

    @Test
    fun `parses negative values`() {
        val l1 = TestUtil.withChecksum("1 25544U 98067A   26325.59122794 -.00002182  00000-0 -11606-4 0  9990")
        val l2 = TestUtil.withChecksum("2 25544  51.6319 241.1476 0007381  50.6945 309.4697 15.49534382596150")
        val tle = TleParser.parse(null, l1, l2)
        assertEquals(-0.00002182, tle.meanMotionDot, 1e-15)
        assertEquals(-0.11606e-4, tle.bstar, 1e-15)
        assertNull(tle.name)
    }

    @Test
    fun `strips leading zero from name line`() {
        val lines = issText.lines()
        val tle = TleParser.parse("0 ISS (ZARYA)", lines[1], lines[2])
        assertEquals("ISS (ZARYA)", tle.name)
    }

    @Test
    fun `parses two-line format and multiple TLEs`() {
        val lines = issText.lines()
        val case = TestUtil.tleCase("SAT_28057")
        val text = "${lines[1]}\n${lines[2]}\n\nSAT\n${case.line1}\n${case.line2}\n"
        val tles = TleParser.parseAll(text)
        assertEquals(listOf(25544, 28057), tles.map { it.noradId })
        assertNull(tles[0].name)
        assertEquals("SAT", tles[1].name)
    }

    @Test
    fun `rejects bad checksum`() {
        val lines = issText.lines()
        val broken = lines[1].dropLast(1) + "0"
        assertThrows(TleParseException::class.java) { TleParser.parse(null, broken, lines[2]) }
    }

    @Test
    fun `rejects wrong line length`() {
        val lines = issText.lines()
        assertThrows(TleParseException::class.java) { TleParser.parse(null, lines[1].dropLast(2), lines[2]) }
    }

    @Test
    fun `rejects mismatched catalog numbers`() {
        val lines = issText.lines()
        val other = TestUtil.tleCase("SAT_28057").line2
        assertThrows(TleParseException::class.java) { TleParser.parse(null, lines[1], other) }
    }
}
