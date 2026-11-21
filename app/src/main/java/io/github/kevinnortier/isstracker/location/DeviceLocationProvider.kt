package io.github.kevinnortier.isstracker.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import io.github.kevinnortier.isstracker.data.settings.LocationFix
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Result of asking for the phone's position. */
sealed interface LocationResult {
    /** [fresh] is false when no new position arrived in time and an older last-known one is returned. */
    data class Success(val fix: LocationFix, val fresh: Boolean = true) : LocationResult
    data object PermissionMissing : LocationResult
    data object LocationDisabled : LocationResult
    data object NoFix : LocationResult
}

/** Which location permission the user has granted. */
enum class LocationPermission { NONE, APPROXIMATE, PRECISE }

/**
 * Gets the phone's position with the framework [LocationManager] (no Google Play Services needed,
 * so it works on GrapheneOS). Uses the AOSP fused provider when available, otherwise GPS, then network.
 */
class DeviceLocationProvider(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    fun permission(): LocationPermission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationPermission.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NONE
    }

    fun isLocationEnabled(): Boolean = locationManager.isLocationEnabled

    /**
     * Requests a fresh position, trying providers in order until one answers.
     * Gives up after roughly [timeoutMillis] in total.
     */
    @SuppressLint("MissingPermission") // checked via permission()
    suspend fun currentLocation(timeoutMillis: Long = 60_000): LocationResult {
        if (permission() == LocationPermission.NONE) return LocationResult.PermissionMissing
        if (!locationManager.isLocationEnabled) return LocationResult.LocationDisabled

        val providers = candidateProviders()
        if (providers.isEmpty()) return LocationResult.LocationDisabled

        // The fused provider already combines GPS and network, so it gets the whole time budget.
        // Without it, try GPS first and then network.
        val deadline = System.currentTimeMillis() + timeoutMillis
        for (provider in providers) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val location = withTimeoutOrNull(remaining) { requestSingle(provider, remaining) }
            if (location != null) return LocationResult.Success(location.toFix(), fresh = true)
            if (provider == LocationManager.FUSED_PROVIDER) break
        }
        // No fresh fix in time: fall back to the newest last-known position, if any.
        return lastKnown()?.let { LocationResult.Success(it, fresh = false) } ?: LocationResult.NoFix
    }

    /** Newest cached position from any provider, without turning on GPS. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): LocationFix? {
        if (permission() == LocationPermission.NONE) return null
        return candidateProviders()
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    private fun candidateProviders(): List<String> = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    ).filter { locationManager.hasProvider(it) && locationManager.isProviderEnabled(it) }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(provider: String, durationMillis: Long): Location? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        // The simple getCurrentLocation() overload gives up after a fixed 30 s, which is often too short
        // for a GPS cold start. An explicit request lets us wait longer and ask for high accuracy.
        val request = LocationRequest.Builder(0L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setDurationMillis(durationMillis)
            .build()
        try {
            locationManager.getCurrentLocation(provider, request, cancel, executor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null) // provider disappeared
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Location.toFix() = LocationFix(
        location = GeodeticLocation(
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            // Android reports altitude above the WGS-84 ellipsoid, which is what the orbit code expects.
            altitudeMeters = if (hasAltitude()) altitude else 0.0,
        ),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        time = Instant.ofEpochMilli(time),
        provider = provider ?: "unknown",
    )
}
