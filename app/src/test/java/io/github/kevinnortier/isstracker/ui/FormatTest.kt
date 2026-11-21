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
    fun `coordinates with hemispheres`() {
        assertEquals("51.4769° N, 0.0005° W", Format.coordinates(51.4769, -0.0005))
        assertEquals("33.8568° S, 151.2153° E", Format.coordinates(-33.8568, 151.2153))
    }

    @Test
    fun `accuracy and metres`() {
        assertEquals("±13 m", Format.accuracy(12.6f))
        assertEquals("46 m", Format.meters(46.2))
    }

    @Test
    fun `day headings`() {
        val today = java.time.LocalDate.of(2026, 11, 22)
        assertEquals("Today", Format.day(today, today))
        assertEquals("Tomorrow", Format.day(today.plusDays(1), today))
        assertEquals("Tuesday 24 November", Format.day(today.plusDays(2), today))
    }

    @Test
    fun countdowns() {
        assertEquals("00:00", Format.countdown(Duration.ofSeconds(-5)))
        assertEquals("04:07", Format.countdown(Duration.ofSeconds(247)))
        assertEquals("59:59", Format.countdown(Duration.ofSeconds(3599)))
        assertEquals("1 h 00 min", Format.countdown(Duration.ofSeconds(3600)))
        assertEquals("26 h 05 min", Format.countdown(Duration.ofMinutes(26 * 60 + 5)))
    }

    @Test
    fun `pass durations and times`() {
        assertEquals("6 min 30 s", Format.passDuration(Duration.ofSeconds(390)))
        assertEquals("45 s", Format.passDuration(Duration.ofSeconds(45)))
        assertEquals("21:14", Format.time(Instant.parse("2026-11-22T21:14:59Z"), ZoneOffset.UTC))
        assertEquals("21:14:59", Format.timeWithSeconds(Instant.parse("2026-11-22T21:14:59Z"), ZoneOffset.UTC))
        assertEquals("45°", Format.degrees0(44.6))
    }

    @Test
    fun `24 hour date time`() {
        assertEquals(
            "2026-11-21 14:11",
            Format.dateTime(Instant.parse("2026-11-21T14:11:22Z"), ZoneOffset.UTC),
        )
    }
}
