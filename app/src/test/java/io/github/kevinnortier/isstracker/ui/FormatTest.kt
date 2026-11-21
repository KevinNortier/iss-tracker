package io.github.kevinnortier.isstracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FormatTest {

    @Test
    fun `compass points`() {
        assertEquals("N", Format.compassPoint(0.0))
        assertEquals("N", Format.compassPoint(359.0))
        assertEquals("N", Format.compassPoint(11.0))
        assertEquals("NNE", Format.compassPoint(12.0))
        assertEquals("E", Format.compassPoint(90.0))
        assertEquals("SE", Format.compassPoint(135.0))
        assertEquals("S", Format.compassPoint(180.0))
        assertEquals("W", Format.compassPoint(270.0))
        assertEquals("NNW", Format.compassPoint(337.5))
        assertEquals("W", Format.compassPoint(-90.0))
    }

    @Test
    fun `degrees with one decimal`() {
        assertEquals("123.4°", Format.degrees1(123.44))
        assertEquals("-5.0°", Format.degrees1(-4.96))
    }

    @Test
    fun durations() {
        assertEquals("0 min", Format.duration(Duration.ofSeconds(30)))
        assertEquals("5 min", Format.duration(Duration.ofMinutes(5)))
        assertEquals("3 h 12 min", Format.duration(Duration.ofMinutes(192)))
        assertEquals("2 d 4 h", Format.duration(Duration.ofHours(52)))
    }

    @Test
    fun `24 hour date time`() {
        assertEquals(
            "2026-11-21 14:11",
            Format.dateTime(Instant.parse("2026-11-21T14:11:22Z"), ZoneOffset.UTC),
        )
    }
}
