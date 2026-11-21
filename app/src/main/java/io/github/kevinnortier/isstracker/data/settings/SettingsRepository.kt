package io.github.kevinnortier.isstracker.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

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

        return AppSettings(
            locationMode = mode,
            savedLocation = saved,
            lastFix = fix,
            minElevationDeg = minElevation,
        )
    }

    private fun location(lat: Double?, lon: Double?, alt: Double?): GeodeticLocation? {
        if (lat == null || lon == null) return null
        return runCatching { GeodeticLocation(lat, lon, alt ?: 0.0) }.getOrNull()
    }
}
