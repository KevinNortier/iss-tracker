package io.github.kevinnortier.isstracker.ui

import java.time.Duration
import java.time.Instant
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

    /** 16-point compass direction for an azimuth in degrees, e.g. 135 → "SE". */
    fun compassPoint(azimuthDeg: Double): String {
        val normalized = ((azimuthDeg % 360.0) + 360.0) % 360.0
        return COMPASS_POINTS[((normalized / 22.5).roundToInt()) % 16]
    }

    /** Degrees with one decimal, e.g. "123.4°". */
    fun degrees1(value: Double): String = String.format(Locale.ROOT, "%.1f°", value)

    fun km(value: Double): String = String.format(Locale.ROOT, "%,.0f km", value)

    fun kmPerSec(value: Double): String = String.format(Locale.ROOT, "%.2f km/s", value)

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
