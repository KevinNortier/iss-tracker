package io.github.kevinnortier.isstracker.data.settings

import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import java.math.BigDecimal
import java.math.RoundingMode

/** Validates a location typed in by the user. */
object LocationInput {

    data class Errors(
        val latitude: String? = null,
        val longitude: String? = null,
        val altitude: String? = null,
    ) {
        val isEmpty get() = latitude == null && longitude == null && altitude == null
    }

    sealed interface Result {
        data class Valid(val location: GeodeticLocation) : Result
        data class Invalid(val errors: Errors) : Result
    }

    /**
     * Parses latitude/longitude in decimal degrees and altitude in metres.
     * Accepts both "." and "," as decimal separator. An empty altitude means 0 m.
     */
    fun parse(latitude: String, longitude: String, altitude: String): Result {
        val lat = number(latitude)
        val lon = number(longitude)
        val alt = if (altitude.isBlank()) 0.0 else number(altitude)

        val errors = Errors(
            latitude = when {
                latitude.isBlank() -> "Required"
                lat == null -> "Not a number"
                lat !in -90.0..90.0 -> "Must be between -90 and 90"
                else -> null
            },
            longitude = when {
                longitude.isBlank() -> "Required"
                lon == null -> "Not a number"
                lon !in -180.0..180.0 -> "Must be between -180 and 180"
                else -> null
            },
            altitude = when {
                alt == null -> "Not a number"
                alt !in -500.0..9000.0 -> "Must be between -500 and 9000 m"
                else -> null
            },
        )
        return if (errors.isEmpty) {
            Result.Valid(GeodeticLocation(lat!!, lon!!, alt!!))
        } else {
            Result.Invalid(errors)
        }
    }

    /**
     * Formats a coordinate for an input field: plain decimal notation (never "5.0E-4"),
     * at most [maxDecimals] decimals, no trailing zeros.
     */
    fun formatForInput(value: Double, maxDecimals: Int = 6): String =
        BigDecimal.valueOf(value)
            .setScale(maxDecimals, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .let { if (it.signum() == 0) BigDecimal.ZERO else it }
            .toPlainString()

    private fun number(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}
