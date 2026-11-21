package io.github.kevinnortier.isstracker.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.Observer
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.location.LocationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

sealed interface LocationStatus {
    data object Idle : LocationStatus
    data object Locating : LocationStatus
    data class Failed(val message: String) : LocationStatus
}

data class LocationUiState(
    val mode: LocationMode = LocationMode.CURRENT,
    /** The observer position in use, or null if there is none yet. */
    val observer: Observer? = null,
    val permission: LocationPermission = LocationPermission.NONE,
    val status: LocationStatus = LocationStatus.Idle,
    /** True once the settings have been read (avoids flashing "no location" at startup). */
    val loaded: Boolean = false,
)

/** Location state and actions shared by the screens that need the observer position. */
class LocationViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class Local(val permission: LocationPermission, val status: LocationStatus = LocationStatus.Idle)

    private val local = MutableStateFlow(Local(container.locationRepository.permission()))
    private val settings = container.settingsRepository.settings

    val uiState: StateFlow<LocationUiState> = combine(settings, local) { settings, local ->
        LocationUiState(
            mode = settings.locationMode,
            observer = settings.resolveObserver(),
            permission = local.permission,
            status = local.status,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationUiState())

    private var locationJob: Job? = null

    /**
     * Called when a screen becomes visible and after a permission dialog closes.
     * Re-checks the permission and gets a new position if the last one is too old.
     */
    fun onScreenVisible() {
        viewModelScope.launch {
            val permission = container.locationRepository.permission()
            local.update { it.copy(permission = permission) }
            val current = settings.first()
            val fixAge = current.lastFix?.let { Duration.between(it.time, clock.instant()) }
            if (current.locationMode == LocationMode.CURRENT &&
                permission != LocationPermission.NONE &&
                (fixAge == null || fixAge > REFRESH_AFTER)
            ) {
                updateLocation()
            }
        }
    }

    /** Asks for a fresh position now. */
    fun updateLocation() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            local.update { it.copy(status = LocationStatus.Locating) }
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
            local.update { Local(container.locationRepository.permission(), status) }
        }
    }

    companion object {
        /** Get a new position when a screen opens if the last one is older than this. */
        val REFRESH_AFTER: Duration = Duration.ofMinutes(15)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                LocationViewModel(app.container)
            }
        }
    }
}
