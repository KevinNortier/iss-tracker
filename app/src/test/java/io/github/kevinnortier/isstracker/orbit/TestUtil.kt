package io.github.kevinnortier.isstracker.orbit

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

internal object TestUtil {

    /** Replaces the last character of a TLE line with a correct checksum. */
    fun withChecksum(line: String): String {
        val sum = line.substring(0, 68).sumOf { c ->
            when {
                c.isDigit() -> c.digitToInt()
                c == '-' -> 1
                else -> 0
            }
        }
        return line.substring(0, 68) + (sum % 10)
    }

    fun tle(case: ReferenceData.TleCase): Tle = TleParser.parse(case.name, case.line1, case.line2)

    fun tleCase(name: String): ReferenceData.TleCase = ReferenceData.tleCases.first { it.name == name }

    /** Angle (degrees) between two directions given as azimuth/elevation in degrees. */
    fun angularSeparationDeg(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        fun unit(az: Double, el: Double): DoubleArray {
            val a = az * PI / 180.0
            val e = el * PI / 180.0
            return doubleArrayOf(cos(e) * cos(a), cos(e) * sin(a), sin(e))
        }
        val u = unit(az1, el1)
        val v = unit(az2, el2)
        val dot = (u[0] * v[0] + u[1] * v[1] + u[2] * v[2]).coerceIn(-1.0, 1.0)
        return acos(dot) * 180.0 / PI
    }
}
