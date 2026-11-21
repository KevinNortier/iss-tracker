package io.github.kevinnortier.isstracker.data.settings

import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import java.time.Instant

/** Where the observer position comes from. */
enum class LocationMode { CURRENT, SAVED }

/** Which moment of a pass a reminder counts down to. */
enum class NotifyTrigger { RISE, APEX }

/** A location the user entered by hand. */
data class SavedLocation(val name: String, val location: GeodeticLocation)

/** A position reported by the phone's location services. */
data class LocationFix(
    val location: GeodeticLocation,
    /** Horizontal accuracy (68 % confidence) in metres, if known. */
    val accuracyMeters: Float?,
    val time: Instant,
    /** Location provider name, e.g. "gps", "network" or "fused". */
    val provider: String,
)

data class AppSettings(
    val locationMode: LocationMode = LocationMode.CURRENT,
    val savedLocation: SavedLocation? = null,
    /** The most recent fix from location services, kept so the app can start without waiting for GPS. */
    val lastFix: LocationFix? = null,
    /** A pass only counts while the satellite is at least this high above the horizon. */
    val minElevationDeg: Double = DEFAULT_MIN_ELEVATION_DEG,
    /** Reminders count down to the rise or to the highest point of the pass. */
    val notifyTrigger: NotifyTrigger = NotifyTrigger.RISE,
    /** How many minutes before the trigger moment the reminder is shown. */
    val notifyLeadMinutes: Int = DEFAULT_NOTIFY_LEAD_MINUTES,
) {
    companion object {
        const val DEFAULT_MIN_ELEVATION_DEG = 0.0
        val MIN_ELEVATION_CHOICES = listOf(0.0, 5.0, 10.0, 20.0)

        const val DEFAULT_NOTIFY_LEAD_MINUTES = 15
        val NOTIFY_LEAD_CHOICES = listOf(5, 10, 15, 30, 60)
    }
}

/** The observer position the app should use right now, and where it came from. */
sealed interface Observer {
    val location: GeodeticLocation

    data class Current(val fix: LocationFix) : Observer {
        override val location get() = fix.location
    }

    data class Saved(val saved: SavedLocation) : Observer {
        override val location get() = saved.location
    }
}

/** Picks the observer from the settings, or null if the chosen source has no position yet. */
fun AppSettings.resolveObserver(): Observer? = when (locationMode) {
    LocationMode.CURRENT -> lastFix?.let { Observer.Current(it) }
    LocationMode.SAVED -> savedLocation?.let { Observer.Saved(it) }
}
