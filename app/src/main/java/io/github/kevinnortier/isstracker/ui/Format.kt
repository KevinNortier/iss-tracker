package io.github.kevinnortier.isstracker.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display formatting helpers (24h clock, degrees with one decimal in live views). */
object Format {

    private val COMPASS_POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    /** 24 h clock time, e.g. "21:14". */
    fun time(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(instant.atZone(zone))

    /** 24 h clock time with seconds, e.g. "21:14:05". */
    fun timeWithSeconds(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_SECONDS.format(instant.atZone(zone))

    /** Day heading: "Today", "Tomorrow" or e.g. "Monday 24 November". */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> DAY.format(date)
    }

    /** Whole degrees, e.g. "45°" (used in lists). */
    fun degrees0(value: Double): String = "${value.roundToInt()}°"

    /** Countdown: "1 h 05 min" for an hour or more, otherwise "mm:ss". */
    fun countdown(d: Duration): String {
        val totalSeconds = d.seconds.coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d h %02d min", hours, minutes)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /** Pass length, e.g. "6 min 30 s". */
    fun passDuration(d: Duration): String {
        val totalSeconds = d.seconds.coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "$minutes min $seconds s" else "$seconds s"
    }

    /** 16-point compass direction for an azimuth in degrees, e.g. 135 → "SE". */
    fun compassPoint(azimuthDeg: Double): String {
        val normalized = ((azimuthDeg % 360.0) + 360.0) % 360.0
        return COMPASS_POINTS[((normalized / 22.5).roundToInt()) % 16]
    }

    /** Degrees with one decimal, e.g. "123.4°". */
    fun degrees1(value: Double): String = String.format(Locale.ROOT, "%.1f°", value)

    fun km(value: Double): String = String.format(Locale.ROOT, "%,.0f km", value)

    fun kmPerSec(value: Double): String = String.format(Locale.ROOT, "%.2f km/s", value)

    fun meters(value: Double): String = String.format(Locale.ROOT, "%.0f m", value)

    fun accuracy(meters: Float): String = String.format(Locale.ROOT, "±%.0f m", meters)

    /** Latitude/longitude with hemisphere letters, e.g. "51.4769° N, 0.0005° W". */
    fun coordinates(latitudeDeg: Double, longitudeDeg: Double): String = String.format(
        Locale.ROOT,
        "%.4f° %s, %.4f° %s",
        abs(latitudeDeg), if (latitudeDeg >= 0) "N" else "S",
        abs(longitudeDeg), if (longitudeDeg >= 0) "E" else "W",
    )

    fun dateTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.format(instant.atZone(zone))

    /** Rough human-readable age, e.g. "5 min", "3 h 12 min", "2 d 4 h". */
    fun duration(d: Duration): String {
        val totalMinutes = abs(d.toMinutes())
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $minutes min"
            else -> "$minutes min"
        }
    }
}
