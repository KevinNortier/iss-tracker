package io.github.kevinnortier.isstracker.ui.satellites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.CatalogGroup
import io.github.kevinnortier.isstracker.data.isLowOrbit
import io.github.kevinnortier.isstracker.orbit.Tle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class AddTab { GROUPS, SEARCH, NORAD }

/** A satellite in a catalog result list. */
data class CatalogItem(val tle: Tle, val lowOrbit: Boolean) {
    val noradId: Int get() = tle.noradId
    val name: String get() = tle.name ?: "#${tle.noradId}"
}

/** A list that is loading, loaded or failed. */
sealed interface ResultList {
    data object Idle : ResultList
    data object Loading : ResultList
    data class Loaded(val items: List<CatalogItem>) : ResultList
    data class Failed(val message: String) : ResultList
}

data class AddSatelliteUiState(
    val tab: AddTab = AddTab.GROUPS,
    val group: CatalogGroup? = null,
    val groupResult: ResultList = ResultList.Idle,
    val searchText: String = "",
    val searchResult: ResultList = ResultList.Idle,
    val noradText: String = "",
    val noradResult: ResultList = ResultList.Idle,
    val trackedIds: Set<Int> = emptySet(),
    val full: Boolean = false,
    /** NORAD IDs being added right now (button disabled meanwhile). */
    val adding: Set<Int> = emptySet(),
    /** One-off message for a snackbar, e.g. "Added SO-50". */
    val message: String? = null,
)

/** Turns catalog TLEs into result rows; low orbits first, each part sorted by name. */
fun toCatalogItems(tles: List<Tle>): List<CatalogItem> = tles
    .distinctBy { it.noradId }
    .map { CatalogItem(it, it.isLowOrbit()) }
    .sortedWith(compareBy<CatalogItem>({ !it.lowOrbit }, { it.name.lowercase() }))

class AddSatelliteViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        const val MIN_SEARCH_LENGTH = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                AddSatelliteViewModel(app.container)
            }
        }
    }

    private val local = MutableStateFlow(AddSatelliteUiState())

    val uiState: StateFlow<AddSatelliteUiState> =
        combine(local, container.settingsRepository.trackedSatellites) { state, tracked ->
            state.copy(trackedIds = tracked.map { it.noradId }.toSet(), full = tracked.size >= Satellite.MAX_TRACKED)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddSatelliteUiState())

    fun selectTab(tab: AddTab) = local.update { it.copy(tab = tab) }

    fun selectGroup(group: CatalogGroup) {
        local.update { it.copy(group = group, groupResult = ResultList.Loading) }
        viewModelScope.launch {
            val result = load { container.catalogRepository.group(group) }
            // Ignore the result if the user picked another group meanwhile.
            local.update { if (it.group == group) it.copy(groupResult = result) else it }
        }
    }

    fun setSearchText(text: String) = local.update { it.copy(searchText = text) }

    fun search() {
        val text = local.value.searchText.trim()
        if (text.length < MIN_SEARCH_LENGTH) return
        local.update { it.copy(searchResult = ResultList.Loading) }
        viewModelScope.launch {
            val result = load { container.catalogRepository.search(text) }
            local.update { it.copy(searchResult = result) }
        }
    }

    fun setNoradText(text: String) = local.update { it.copy(noradText = text.filter(Char::isDigit).take(9)) }

    fun lookup() {
        val id = local.value.noradText.toIntOrNull()?.takeIf { it > 0 } ?: return
        local.update { it.copy(noradResult = ResultList.Loading) }
        viewModelScope.launch {
            val result = load { listOfNotNull(container.catalogRepository.lookup(id)) }
            local.update { it.copy(noradResult = result) }
        }
    }

    fun add(item: CatalogItem) {
        if (!item.lowOrbit || item.noradId in local.value.adding) return
        val fromGroup = local.value.group.takeIf { local.value.tab == AddTab.GROUPS }
        local.update { it.copy(adding = it.adding + item.noradId) }
        viewModelScope.launch {
            // Bright if it comes from a "bright" group, otherwise check those groups' lists.
            val bright = fromGroup?.bright ?: container.catalogRepository.isBright(item.noradId)
            val added = container.addSatellite(item.tle, bright)
            local.update {
                it.copy(
                    adding = it.adding - item.noradId,
                    message = if (added) "Added ${item.name}" else "The list is full (${Satellite.MAX_TRACKED})",
                )
            }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private suspend fun load(block: suspend () -> List<Tle>): ResultList = try {
        ResultList.Loaded(toCatalogItems(block()))
    } catch (e: IOException) {
        ResultList.Failed("Couldn't reach CelesTrak: ${e.message ?: "network error"}")
    }
}
