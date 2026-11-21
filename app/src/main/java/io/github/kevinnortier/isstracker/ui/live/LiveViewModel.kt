package io.github.kevinnortier.isstracker.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.CachedTle
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.AppSettings
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.Observer
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.location.LocationResult
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.orbit.SatelliteTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

sealed interface TleStatus {
    data object Loading : TleStatus
    data class Failed(val message: String) : TleStatus
    data class Loaded(val cached: CachedTle) : TleStatus
}

sealed interface LocationStatus {
    data object Idle : LocationStatus
    data object Locating : LocationStatus
    data class Failed(val message: String) : LocationStatus
}

data class LiveUiState(
    val satelliteName: String,
    val tleStatus: TleStatus = TleStatus.Loading,
    /** Message about the last TLE refresh attempt (error or "already up to date"), if any. */
    val refreshMessage: String? = null,
    val refreshing: Boolean = false,
    val locationMode: LocationMode = LocationMode.CURRENT,
    /** The observer position in use, or null if there is none yet. */
    val observer: Observer? = null,
    val locationPermission: LocationPermission = LocationPermission.NONE,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    val observation: Observation? = null,
    /** Error while calculating the position (e.g. a satellite that has decayed). */
    val calculationError: String? = null,
    val now: Instant = Instant.EPOCH,
)

class LiveViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class TleSection(
        val status: TleStatus = TleStatus.Loading,
        val message: String? = null,
        val refreshing: Boolean = false,
    )

    private data class LocationSection(
        val permission: LocationPermission = LocationPermission.NONE,
        val status: LocationStatus = LocationStatus.Idle,
    )

    /** A tracker for the current TLE + observer, or the reason there is none. */
    private data class TrackerState(val tracker: SatelliteTracker?, val error: String?)

    private val tleSection = MutableStateFlow(TleSection())
    private val locationSection = MutableStateFlow(LocationSection(permission = container.locationRepository.permission()))
    private val settings = container.settingsRepository.settings

    private val trackerState = combine(
        tleSection.map { (it.status as? TleStatus.Loaded)?.cached?.tle }.distinctUntilChanged(),
        settings.map { it.resolveObserver()?.location }.distinctUntilChanged(),
    ) { tle, location ->
        if (tle == null || location == null) {
            TrackerState(null, null)
        } else {
            try {
                TrackerState(SatelliteTracker(tle, location), null)
            } catch (e: RuntimeException) {
                TrackerState(null, e.message ?: e.toString())
            }
        }
    }

    /** Emits the current time once per second, aligned to whole seconds. */
    private val ticker = flow {
        while (true) {
            val now = clock.instant()
            emit(now)
            delay(1000L - now.toEpochMilli() % 1000L)
        }
    }

    val uiState: StateFlow<LiveUiState> = combine(
        tleSection, locationSection, settings, trackerState, ticker,
    ) { tle, location, settings, trackerState, now ->
        var calculationError = trackerState.error
        val observation = trackerState.tracker?.let { tracker ->
            try {
                tracker.observe(now)
            } catch (e: RuntimeException) {
                calculationError = e.message ?: e.toString()
                null
            }
        }
        LiveUiState(
            satelliteName = container.satellite.displayName,
            tleStatus = tle.status,
            refreshMessage = tle.message,
            refreshing = tle.refreshing,
            locationMode = settings.locationMode,
            observer = settings.resolveObserver(),
            locationPermission = location.permission,
            locationStatus = location.status,
            observation = observation,
            calculationError = calculationError,
            now = now,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LiveUiState(satelliteName = container.satellite.displayName),
    )

    private var locationJob: Job? = null

    init {
        loadTle(forceRefresh = false)
    }

    // ---- TLE ----

    fun refreshTle() = loadTle(forceRefresh = true)

    private fun loadTle(forceRefresh: Boolean) {
        viewModelScope.launch {
            tleSection.update { it.copy(refreshing = true, message = null) }
            try {
                val result = container.tleRepository.getTle(container.satellite.noradId, forceRefresh)
                val message = when {
                    result.refreshError != null ->
                        "Download failed, using saved data: ${result.refreshError.message}"
                    forceRefresh && !result.downloaded ->
                        "Already up to date. CelesTrak is checked at most every " +
                            "${TleRepository.MIN_REFRESH_INTERVAL.toHours()} hours."
                    else -> null
                }
                tleSection.update { TleSection(TleStatus.Loaded(result.cached), message, refreshing = false) }
            } catch (e: IOException) {
                tleSection.update {
                    it.copy(
                        status = it.status as? TleStatus.Loaded
                            ?: TleStatus.Failed(e.message ?: "Could not load orbit data"),
                        refreshing = false,
                    )
                }
            }
        }
    }

    // ---- Location ----

    /**
     * Called when the screen becomes visible and after a permission dialog closes.
     * Re-checks the permission and gets a new position if the last one is too old.
     */
    fun onScreenVisible() {
        viewModelScope.launch {
            val permission = container.locationRepository.permission()
            locationSection.update { it.copy(permission = permission) }
            val current: AppSettings = settings.first()
            val fixAge = current.lastFix?.let { Duration.between(it.time, clock.instant()) }
            if (current.locationMode == LocationMode.CURRENT &&
                permission != LocationPermission.NONE &&
                (fixAge == null || fixAge > LOCATION_REFRESH_AFTER)
            ) {
                updateLocation()
            }
        }
    }

    /** Asks for a fresh position now. */
    fun updateLocation() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationSection.update { it.copy(status = LocationStatus.Locating) }
            val status = when (val result = container.locationRepository.refreshCurrentLocation()) {
                is LocationResult.Success -> if (result.fresh) {
                    LocationStatus.Idle
                } else {
                    LocationStatus.Failed(
                        "No new position within a minute, so the last known one is shown. " +
                            "GPS works best near a window or outdoors.",
                    )
                }
                LocationResult.PermissionMissing -> LocationStatus.Failed("Location permission is not granted.")
                LocationResult.LocationDisabled -> LocationStatus.Failed("Location is turned off on the phone.")
                LocationResult.NoFix -> LocationStatus.Failed(
                    "Couldn't get a position. Try again with a clear view of the sky, or use a saved location.",
                )
            }
            locationSection.update {
                it.copy(permission = container.locationRepository.permission(), status = status)
            }
        }
    }

    companion object {
        /** TLEs older than this are getting inaccurate (the ISS reboosts regularly). */
        val TLE_OLD_AFTER: Duration = Duration.ofDays(3)

        /** Get a new position when the screen opens if the last one is older than this. */
        val LOCATION_REFRESH_AFTER: Duration = Duration.ofMinutes(15)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                LiveViewModel(app.container)
            }
        }
    }
}
