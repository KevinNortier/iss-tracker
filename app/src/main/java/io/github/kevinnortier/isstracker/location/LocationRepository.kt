package io.github.kevinnortier.isstracker.location

import io.github.kevinnortier.isstracker.data.settings.LocationFix
import io.github.kevinnortier.isstracker.data.settings.Observer
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Combines the location settings with the phone's location services. */
class LocationRepository(
    private val settings: SettingsRepository,
    private val provider: DeviceLocationProvider,
) {
    /** The observer position to use, or null if none is available yet. */
    val observer: Flow<Observer?> = settings.settings.map { it.resolveObserver() }.distinctUntilChanged()

    fun permission(): LocationPermission = provider.permission()

    /**
     * Asks location services for a fresh position and stores it as the last fix,
     * unless the stored fix is newer (e.g. when only an old last-known position was available).
     */
    suspend fun refreshCurrentLocation(): LocationResult {
        val result = provider.currentLocation()
        if (result is LocationResult.Success) {
            val stored = settings.settings.first().lastFix
            if (shouldStore(result.fix, stored)) settings.setLastFix(result.fix)
        }
        return result
    }

    companion object {
        fun shouldStore(new: LocationFix, stored: LocationFix?): Boolean =
            stored == null || !new.time.isBefore(stored.time)
    }
}
