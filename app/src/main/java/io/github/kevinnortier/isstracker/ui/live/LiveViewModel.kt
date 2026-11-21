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
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.orbit.SatelliteTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
    val locationDescription: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val tleStatus: TleStatus = TleStatus.Loading,
    /** Message about the last refresh attempt (error or "already up to date"), if any. */
    val refreshMessage: String? = null,
    val refreshing: Boolean = false,
    val observation: Observation? = null,
    /** Error while calculating the position (e.g. a satellite that has decayed). */
    val calculationError: String? = null,
    val now: Instant = Instant.EPOCH,
)

class LiveViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private val baseState = MutableStateFlow(
        LiveUiState(
            satelliteName = container.satellite.displayName,
            locationDescription = container.observerLocation.description,
            latitudeDeg = container.observerLocation.location.latitudeDeg,
            longitudeDeg = container.observerLocation.location.longitudeDeg,
        ),
    )

    private var tracker: SatelliteTracker? = null

    /** Emits the current time once per second, aligned to whole seconds. */
    private val ticker = flow {
        while (true) {
            val now = clock.instant()
            emit(now)
            delay(1000L - now.toEpochMilli() % 1000L)
        }
    }

    val uiState: StateFlow<LiveUiState> = combine(baseState, ticker) { state, now ->
        val t = tracker
        if (t == null) {
            state.copy(now = now)
        } else {
            try {
                state.copy(now = now, observation = t.observe(now), calculationError = null)
            } catch (e: RuntimeException) {
                state.copy(now = now, observation = null, calculationError = e.message ?: e.toString())
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), baseState.value)

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        viewModelScope.launch {
            baseState.update { it.copy(refreshing = true, refreshMessage = null) }
            try {
                val result = container.tleRepository.getTle(container.satellite.noradId, forceRefresh)
                tracker = try {
                    SatelliteTracker(result.cached.tle, container.observerLocation.location)
                } catch (e: RuntimeException) {
                    baseState.update { it.copy(calculationError = e.message) }
                    null
                }
                val message = when {
                    result.refreshError != null ->
                        "Download failed, using saved data: ${result.refreshError.message}"
                    forceRefresh && !result.downloaded ->
                        "Already up to date. CelesTrak is checked at most every " +
                            "${TleRepository.MIN_REFRESH_INTERVAL.toHours()} hours."
                    else -> null
                }
                baseState.update {
                    it.copy(tleStatus = TleStatus.Loaded(result.cached), refreshMessage = message, refreshing = false)
                }
            } catch (e: IOException) {
                baseState.update {
                    it.copy(
                        tleStatus = if (it.tleStatus is TleStatus.Loaded) it.tleStatus else TleStatus.Failed(
                            e.message ?: "Could not load orbit data",
                        ),
                        refreshing = false,
                    )
                }
            }
        }
    }

    companion object {
        /** TLEs older than this are getting inaccurate (the ISS reboosts regularly). */
        val TLE_OLD_AFTER: Duration = Duration.ofDays(3)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                LiveViewModel(app.container)
            }
        }
    }
}
