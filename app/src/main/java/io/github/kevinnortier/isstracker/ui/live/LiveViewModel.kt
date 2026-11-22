package io.github.kevinnortier.isstracker.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.CachedTle
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.orbit.SatelliteTracker
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

data class LiveUiState(
    val satelliteName: String,
    /** Bright enough to see with the eye when sunlit. */
    val bright: Boolean = false,
    val tleStatus: TleStatus = TleStatus.Loading,
    /** Message about the last TLE refresh attempt (error or "already up to date"), if any. */
    val refreshMessage: String? = null,
    val refreshing: Boolean = false,
    val hasObserver: Boolean = false,
    val observation: Observation? = null,
    /** Error while calculating the position (e.g. a satellite that has decayed). */
    val calculationError: String? = null,
    val now: Instant = Instant.EPOCH,
)

class LiveViewModel(
    private val container: AppContainer,
    private val noradId: Int,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class RefreshState(
        val failure: String? = null,
        val message: String? = null,
        val refreshing: Boolean = false,
    )

    /** A tracker for the current TLE + observer, or the reason there is none. */
    private data class TrackerState(val tracker: SatelliteTracker?, val error: String?)

    private val refreshState = MutableStateFlow(RefreshState())
    private val tle = container.tleRepository.latest(noradId)
    private val satellite = container.settingsRepository.trackedSatellites
        .map { list -> list.firstOrNull { it.noradId == noradId } ?: Satellite(noradId, "#$noradId") }
        .distinctUntilChanged()
    private val observerLocation = container.settingsRepository.settings
        .map { it.resolveObserver()?.location }
        .distinctUntilChanged()

    private val trackerState = combine(tle.map { it?.tle }.distinctUntilChanged(), observerLocation) { tle, location ->
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

    val uiState: StateFlow<LiveUiState> = combine(
        combine(tle, satellite, ::Pair), refreshState, observerLocation, trackerState, secondTicker(clock),
    ) { (cached, satellite), refresh, location, trackerState, now ->
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
            satelliteName = satellite.displayName,
            bright = satellite.bright,
            tleStatus = when {
                cached != null -> TleStatus.Loaded(cached)
                refresh.failure != null -> TleStatus.Failed(refresh.failure)
                else -> TleStatus.Loading
            },
            refreshMessage = refresh.message,
            refreshing = refresh.refreshing,
            hasObserver = location != null,
            observation = observation,
            calculationError = calculationError,
            now = now,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LiveUiState(satelliteName = ""),
    )

    init {
        loadTle(forceRefresh = false)
    }

    fun refreshTle() = loadTle(forceRefresh = true)

    private fun loadTle(forceRefresh: Boolean) {
        viewModelScope.launch {
            refreshState.update { it.copy(refreshing = true, message = null) }
            try {
                val result = container.tleRepository.getTle(noradId, forceRefresh)
                val message = when {
                    result.refreshError != null ->
                        "Download failed, using saved data: ${result.refreshError.message}"
                    forceRefresh && !result.downloaded ->
                        "Already up to date. CelesTrak is checked at most every " +
                            "${TleRepository.MIN_REFRESH_INTERVAL.toHours()} hours."
                    else -> null
                }
                refreshState.update { RefreshState(message = message) }
            } catch (e: IOException) {
                refreshState.update { RefreshState(failure = e.message ?: "Could not load orbit data") }
            }
        }
    }

    companion object {
        /** TLEs older than this are getting inaccurate (low orbits change quickly, the ISS reboosts regularly). */
        val TLE_OLD_AFTER: Duration = Duration.ofDays(3)

        fun factory(noradId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                LiveViewModel(app.container, noradId)
            }
        }
    }
}
