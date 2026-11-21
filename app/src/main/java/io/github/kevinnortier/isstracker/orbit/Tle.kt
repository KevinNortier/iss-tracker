package io.github.kevinnortier.isstracker.orbit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * A parsed two-line element set (TLE).
 *
 * Angles are stored in degrees and mean motion in revolutions per day, exactly as written in the
 * TLE. [Sgp4] converts them to the units it needs.
 */
data class Tle(
    /** Satellite name from the optional title line, or null if there was none. */
    val name: String?,
    val line1: String,
    val line2: String,
    /** NORAD catalog number, e.g. 25544 for the ISS. */
    val noradId: Int,
    /** Epoch of the element set (UTC). */
    val epoch: Instant,
    /** First derivative of mean motion divided by 2 (rev/day²). */
    val meanMotionDot: Double,
    /** Second derivative of mean motion divided by 6 (rev/day³). */
    val meanMotionDdot: Double,
    /** B* drag term (1/earth radii). */
    val bstar: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val eccentricity: Double,
    val argOfPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    /** Mean motion (rev/day). */
    val meanMotion: Double,
)

class TleParseException(message: String) : IllegalArgumentException(message)

object TleParser {

    /**
     * Parses text with one or more TLEs in either 2-line or 3-line (name + 2 lines) format,
     * e.g. the response from CelesTrak's GP API.
     */
    fun parseAll(text: String): List<Tle> {
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val result = mutableListOf<Tle>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ")) {
                result += parse(null, lines[i], lines[i + 1])
                i += 2
            } else if (i + 2 < lines.size && lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ")) {
                result += parse(lines[i], lines[i + 1], lines[i + 2])
                i += 3
            } else {
                throw TleParseException("Unexpected line ${i + 1}: \"${lines[i]}\"")
            }
        }
        return result
    }

    /** Parses a single TLE. [name] is the optional title line (a leading "0 " is removed). */
    fun parse(name: String?, line1: String, line2: String): Tle {
        val l1 = line1.trimEnd()
        val l2 = line2.trimEnd()
        if (l1.length != 69 || l2.length != 69) {
            throw TleParseException("TLE lines must be 69 characters long")
        }
        if (l1[0] != '1' || l2[0] != '2') throw TleParseException("Lines must start with '1' and '2'")
        verifyChecksum(l1, 1)
        verifyChecksum(l2, 2)

        val noradId = field(l1, 2, 7, "catalog number").trim().toIntOrNull()
            ?: throw TleParseException("Invalid catalog number")
        val noradId2 = field(l2, 2, 7, "catalog number").trim().toIntOrNull()
        if (noradId != noradId2) throw TleParseException("Catalog numbers in line 1 and 2 differ")

        val twoDigitYear = number(l1, 18, 20, "epoch year").toInt()
        val year = if (twoDigitYear < 57) 2000 + twoDigitYear else 1900 + twoDigitYear
        val dayOfYear = number(l1, 20, 32, "epoch day")

        return Tle(
            name = name?.trim()?.removePrefix("0 ")?.trim()?.ifEmpty { null },
            line1 = l1,
            line2 = l2,
            noradId = noradId,
            epoch = epochToInstant(year, dayOfYear),
            meanMotionDot = number(l1, 33, 43, "mean motion dot"),
            meanMotionDdot = impliedDecimal(l1, 44, 52, "mean motion ddot"),
            bstar = impliedDecimal(l1, 53, 61, "B*"),
            inclinationDeg = number(l2, 8, 16, "inclination"),
            raanDeg = number(l2, 17, 25, "RAAN"),
            eccentricity = ("0." + field(l2, 26, 33, "eccentricity").trim()).toDoubleOrNull()
                ?: throw TleParseException("Invalid eccentricity"),
            argOfPerigeeDeg = number(l2, 34, 42, "argument of perigee"),
            meanAnomalyDeg = number(l2, 43, 51, "mean anomaly"),
            meanMotion = number(l2, 52, 63, "mean motion"),
        )
    }

    /** Day 1.0 = January 1st 00:00 UTC. */
    private fun epochToInstant(year: Int, dayOfYear: Double): Instant {
        val startOfYear = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val nanos = ((dayOfYear - 1.0) * 86_400e9).roundToLong()
        return startOfYear.plusNanos(nanos)
    }

    private fun verifyChecksum(line: String, lineNo: Int) {
        val sum = line.substring(0, 68).sumOf { c ->
            when {
                c.isDigit() -> c.digitToInt()
                c == '-' -> 1
                else -> 0
            }
        }
        val expected = line[68].digitToIntOrNull()
        if (expected != sum % 10) throw TleParseException("Checksum mismatch in line $lineNo")
    }

    private fun field(line: String, start: Int, end: Int, what: String): String =
        if (line.length >= end) line.substring(start, end) else throw TleParseException("Missing $what")

    private fun number(line: String, start: Int, end: Int, what: String): Double {
        val s = field(line, start, end, what).trim().replace(" ", "")
        if (s.isEmpty()) return 0.0
        return s.toDoubleOrNull() ?: throw TleParseException("Invalid $what: \"$s\"")
    }

    /** Parses the TLE "implied decimal" format, e.g. " 12345-3" = 0.12345e-3, "-11606-4" = -0.11606e-4. */
    private fun impliedDecimal(line: String, start: Int, end: Int, what: String): Double {
        val s = field(line, start, end, what).trim()
        if (s.isEmpty()) return 0.0
        val match = Regex("""^([+-]?)(\d+)([+-]\d)$""").matchEntire(s)
            ?: throw TleParseException("Invalid $what: \"$s\"")
        val (sign, mantissa, exponent) = match.destructured
        return "${sign}0.${mantissa}e$exponent".toDoubleOrNull()
            ?: throw TleParseException("Invalid $what: \"$s\"")
    }
}
