package io.github.kevinnortier.isstracker.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.TrackedSatellitesCodec
import io.github.kevinnortier.isstracker.notify.PassKey
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.sensors.HeadingSource
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/** Reads and writes [AppSettings] with Jetpack DataStore (Preferences). */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val LOCATION_MODE = stringPreferencesKey("location_mode")

        val SAVED_NAME = stringPreferencesKey("saved_name")
        val SAVED_LAT = doublePreferencesKey("saved_lat")
        val SAVED_LON = doublePreferencesKey("saved_lon")
        val SAVED_ALT = doublePreferencesKey("saved_alt_m")

        val FIX_LAT = doublePreferencesKey("fix_lat")
        val FIX_LON = doublePreferencesKey("fix_lon")
        val FIX_ALT = doublePreferencesKey("fix_alt_m")
        val FIX_ACCURACY = floatPreferencesKey("fix_accuracy_m")
        val FIX_TIME = longPreferencesKey("fix_time_ms")
        val FIX_PROVIDER = stringPreferencesKey("fix_provider")

        val MIN_ELEVATION = doublePreferencesKey("min_elevation_deg")

        val NOTIFY_TRIGGER = stringPreferencesKey("notify_trigger")
        val NOTIFY_LEAD = intPreferencesKey("notify_lead_min")
        val NOTIFY_PASSES = stringSetPreferencesKey("notify_passes")
        val SCHEDULED_REMINDERS = stringSetPreferencesKey("scheduled_reminders")

        val POINTING_AXIS = stringPreferencesKey("pointing_axis")
        val HEADING_SOURCE = stringPreferencesKey("heading_source")

        val THEME_MODE = stringPreferencesKey("theme_mode")

        /** Absent = never set (track the default, the ISS); empty text = the user removed everything. */
        val TRACKED_SATELLITES = stringPreferencesKey("tracked_satellites")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    // ---- Tracked satellites ----

    /** The satellites the user tracks, in the user's order. */
    val trackedSatellites: Flow<List<Satellite>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs.trackedSatellites() }
        .distinctUntilChanged()

    suspend fun setTrackedSatellites(satellites: List<Satellite>) {
        dataStore.edit {
            it[Keys.TRACKED_SATELLITES] = TrackedSatellitesCodec.encode(satellites.distinctBy { s -> s.noradId })
        }
    }

    /**
     * Adds [satellite] to the end of the list (or updates its name/brightness if already tracked).
     * Returns false if the list is full ([Satellite.MAX_TRACKED]).
     */
    suspend fun addTrackedSatellite(satellite: Satellite): Boolean {
        var added = true
        dataStore.edit {
            val current = it.trackedSatellites()
            val index = current.indexOfFirst { s -> s.noradId == satellite.noradId }
            val updated = when {
                index >= 0 -> current.toMutableList().also { list -> list[index] = satellite }
                current.size >= Satellite.MAX_TRACKED -> { added = false; current }
                else -> current + satellite
            }
            it[Keys.TRACKED_SATELLITES] = TrackedSatellitesCodec.encode(updated)
        }
        return added
    }

    /** Stops tracking a satellite and forgets its reminders. (The caller deletes its cached TLE.) */
    suspend fun removeTrackedSatellite(noradId: Int) {
        dataStore.edit {
            val updated = it.trackedSatellites().filter { s -> s.noradId != noradId }
            it[Keys.TRACKED_SATELLITES] = TrackedSatellitesCodec.encode(updated)
            it[Keys.NOTIFY_PASSES] = it[Keys.NOTIFY_PASSES].orEmpty()
                .filter { encoded -> PassKey.decode(encoded)?.noradId != noradId }
                .toSet()
        }
    }

    private fun Preferences.trackedSatellites(): List<Satellite> =
        this[Keys.TRACKED_SATELLITES]?.let(TrackedSatellitesCodec::decode) ?: Satellite.DEFAULT_TRACKED

    suspend fun setLocationMode(mode: LocationMode) {
        dataStore.edit { it[Keys.LOCATION_MODE] = mode.name }
    }

    suspend fun setSavedLocation(saved: SavedLocation) {
        dataStore.edit {
            it[Keys.SAVED_NAME] = saved.name
            it[Keys.SAVED_LAT] = saved.location.latitudeDeg
            it[Keys.SAVED_LON] = saved.location.longitudeDeg
            it[Keys.SAVED_ALT] = saved.location.altitudeMeters
        }
    }

    suspend fun setLastFix(fix: LocationFix) {
        dataStore.edit {
            it[Keys.FIX_LAT] = fix.location.latitudeDeg
            it[Keys.FIX_LON] = fix.location.longitudeDeg
            it[Keys.FIX_ALT] = fix.location.altitudeMeters
            if (fix.accuracyMeters != null) it[Keys.FIX_ACCURACY] = fix.accuracyMeters else it.remove(Keys.FIX_ACCURACY)
            it[Keys.FIX_TIME] = fix.time.toEpochMilli()
            it[Keys.FIX_PROVIDER] = fix.provider
        }
    }

    suspend fun setMinElevation(degrees: Double) {
        require(degrees in 0.0..45.0) { "Minimum elevation must be between 0 and 45 degrees" }
        dataStore.edit { it[Keys.MIN_ELEVATION] = degrees }
    }

    suspend fun setNotifyTrigger(trigger: NotifyTrigger) {
        dataStore.edit { it[Keys.NOTIFY_TRIGGER] = trigger.name }
    }

    suspend fun setNotifyLeadMinutes(minutes: Int) {
        require(minutes in 0..180) { "Lead time must be between 0 and 180 minutes" }
        dataStore.edit { it[Keys.NOTIFY_LEAD] = minutes }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setHeadingSource(source: HeadingSource) {
        dataStore.edit { it[Keys.HEADING_SOURCE] = source.name }
    }

    suspend fun setPointingAxis(axis: PointingAxis) {
        dataStore.edit { it[Keys.POINTING_AXIS] = axis.name }
    }

    /** Passes the user wants a reminder for. */
    val selectedPasses: Flow<Set<PassKey>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.NOTIFY_PASSES].orEmpty().mapNotNull(PassKey::decode).toSet() }
        .distinctUntilChanged()

    suspend fun setPassSelected(key: PassKey, selected: Boolean) {
        dataStore.edit {
            val current = it[Keys.NOTIFY_PASSES].orEmpty()
            it[Keys.NOTIFY_PASSES] = if (selected) current + key.encode() else current - key.encode()
        }
    }

    suspend fun setSelectedPasses(keys: Set<PassKey>) {
        dataStore.edit { it[Keys.NOTIFY_PASSES] = keys.map(PassKey::encode).toSet() }
    }

    /** Reminders that currently have an alarm set (so they can be cancelled later). */
    suspend fun scheduledReminders(): Set<PassKey> =
        dataStore.data.first()[Keys.SCHEDULED_REMINDERS].orEmpty().mapNotNull(PassKey::decode).toSet()

    suspend fun setScheduledReminders(keys: Set<PassKey>) {
        dataStore.edit { it[Keys.SCHEDULED_REMINDERS] = keys.map(PassKey::encode).toSet() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val mode = this[Keys.LOCATION_MODE]
            ?.let { name -> LocationMode.entries.firstOrNull { it.name == name } }
            ?: LocationMode.CURRENT

        val saved = location(this[Keys.SAVED_LAT], this[Keys.SAVED_LON], this[Keys.SAVED_ALT])
            ?.let { SavedLocation(this[Keys.SAVED_NAME].orEmpty(), it) }

        val fixLocation = location(this[Keys.FIX_LAT], this[Keys.FIX_LON], this[Keys.FIX_ALT])
        val fixTime = this[Keys.FIX_TIME]
        val fix = if (fixLocation != null && fixTime != null) {
            LocationFix(
                location = fixLocation,
                accuracyMeters = this[Keys.FIX_ACCURACY],
                time = Instant.ofEpochMilli(fixTime),
                provider = this[Keys.FIX_PROVIDER].orEmpty(),
            )
        } else {
            null
        }

        val minElevation = this[Keys.MIN_ELEVATION]?.takeIf { it in 0.0..45.0 }
            ?: AppSettings.DEFAULT_MIN_ELEVATION_DEG

        val trigger = this[Keys.NOTIFY_TRIGGER]
            ?.let { name -> NotifyTrigger.entries.firstOrNull { it.name == name } }
            ?: NotifyTrigger.RISE
        val lead = this[Keys.NOTIFY_LEAD]?.takeIf { it in 0..180 } ?: AppSettings.DEFAULT_NOTIFY_LEAD_MINUTES

        val axis = this[Keys.POINTING_AXIS]
            ?.let { name -> PointingAxis.entries.firstOrNull { it.name == name } }
            ?: PointingAxis.BACK

        val headingSource = this[Keys.HEADING_SOURCE]
            ?.let { name -> HeadingSource.entries.firstOrNull { it.name == name } }
            ?: HeadingSource.COMPASS

        val theme = this[Keys.THEME_MODE]
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM

        return AppSettings(
            locationMode = mode,
            savedLocation = saved,
            lastFix = fix,
            minElevationDeg = minElevation,
            notifyTrigger = trigger,
            notifyLeadMinutes = lead,
            pointingAxis = axis,
            headingSource = headingSource,
            themeMode = theme,
        )
    }

    private fun location(lat: Double?, lon: Double?, alt: Double?): GeodeticLocation? {
        if (lat == null || lon == null) return null
        return runCatching { GeodeticLocation(lat, lon, alt ?: 0.0) }.getOrNull()
    }
}
