package io.github.kevinnortier.isstracker.ui.satellites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** A tracked satellite and when its orbit data is from. */
data class TrackedRow(val satellite: Satellite, val tleEpoch: Instant?)

data class SatellitesUiState(
    val loading: Boolean = true,
    val rows: List<TrackedRow> = emptyList(),
    val now: Instant = Instant.EPOCH,
) {
    val full: Boolean get() = rows.size >= Satellite.MAX_TRACKED
}

/** Settings → Satellites: the tracked list, with remove and reorder. */
@OptIn(ExperimentalCoroutinesApi::class)
class SatellitesViewModel(private val container: AppContainer) : ViewModel() {

    private val tracked = container.settingsRepository.trackedSatellites

    private val rows = tracked.flatMapLatest { satellites ->
        satellites.forEach { container.tleRepository.getCached(it.noradId) } // load what's on disk
        if (satellites.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(satellites.map { s -> container.tleRepository.latest(s.noradId) }) { cached ->
                satellites.mapIndexed { i, s -> TrackedRow(s, cached[i]?.tle?.epoch) }
            }
        }
    }

    val uiState: StateFlow<SatellitesUiState> = combine(rows, secondTicker()) { rows, now ->
        SatellitesUiState(loading = false, rows = rows, now = now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SatellitesUiState())

    fun remove(noradId: Int) {
        viewModelScope.launch { container.removeSatellite(noradId) }
    }

    /** Moves the satellite at [index] one place up (-1) or down (+1). */
    fun move(index: Int, by: Int) {
        val list = uiState.value.rows.map { it.satellite }.toMutableList()
        val target = index + by
        if (index !in list.indices || target !in list.indices) return
        list.add(target, list.removeAt(index))
        viewModelScope.launch { container.settingsRepository.setTrackedSatellites(list) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                SatellitesViewModel(app.container)
            }
        }
    }
}
