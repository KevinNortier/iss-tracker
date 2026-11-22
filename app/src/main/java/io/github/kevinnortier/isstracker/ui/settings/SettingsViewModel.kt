package io.github.kevinnortier.isstracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.settings.AppSettings
import io.github.kevinnortier.isstracker.data.settings.LocationInput
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.NotifyTrigger
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import io.github.kevinnortier.isstracker.data.settings.SavedLocation
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The text fields for entering a saved location. */
data class SavedLocationForm(
    val name: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val altitude: String = "",
    val errors: LocationInput.Errors = LocationInput.Errors(),
    /** Shown after a successful save. */
    val message: String? = null,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val form: SavedLocationForm = SavedLocationForm(),
)

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    private val form = MutableStateFlow(SavedLocationForm())

    val uiState: StateFlow<SettingsUiState> = combine(repository.settings, form) { settings, form ->
        SettingsUiState(settings, form)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        // Pre-fill the form with the stored saved location.
        viewModelScope.launch {
            repository.settings.first().savedLocation?.let { saved ->
                form.update { if (it == SavedLocationForm()) saved.toForm() else it }
            }
        }
    }

    fun setLocationMode(mode: LocationMode) {
        viewModelScope.launch { repository.setLocationMode(mode) }
    }

    fun setMinElevation(degrees: Double) {
        viewModelScope.launch { repository.setMinElevation(degrees) }
    }

    fun setNotifyTrigger(trigger: NotifyTrigger) {
        viewModelScope.launch { repository.setNotifyTrigger(trigger) }
    }

    fun setPointingAxis(axis: PointingAxis) {
        viewModelScope.launch { repository.setPointingAxis(axis) }
    }

    fun setNotifyLeadMinutes(minutes: Int) {
        viewModelScope.launch { repository.setNotifyLeadMinutes(minutes) }
    }

    fun updateForm(
        name: String = form.value.name,
        latitude: String = form.value.latitude,
        longitude: String = form.value.longitude,
        altitude: String = form.value.altitude,
    ) {
        form.update {
            // Clear the error of a field as soon as the user edits it.
            val errors = it.errors.copy(
                latitude = it.errors.latitude.takeIf { _ -> latitude == it.latitude },
                longitude = it.errors.longitude.takeIf { _ -> longitude == it.longitude },
                altitude = it.errors.altitude.takeIf { _ -> altitude == it.altitude },
            )
            it.copy(
                name = name,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                errors = errors,
                message = null,
            )
        }
    }

    /** Copies the last position from location services into the form. */
    fun fillFromLastFix() {
        val fix = uiState.value.settings.lastFix ?: return
        form.update {
            it.copy(
                latitude = LocationInput.formatForInput(fix.location.latitudeDeg, maxDecimals = 5),
                longitude = LocationInput.formatForInput(fix.location.longitudeDeg, maxDecimals = 5),
                altitude = LocationInput.formatForInput(fix.location.altitudeMeters, maxDecimals = 0),
                errors = LocationInput.Errors(),
                message = null,
            )
        }
    }

    /** Validates the form; if valid, stores it and switches to the saved location. */
    fun saveLocation() {
        val f = form.value
        when (val result = LocationInput.parse(f.latitude, f.longitude, f.altitude)) {
            is LocationInput.Result.Invalid -> form.update { it.copy(errors = result.errors, message = null) }
            is LocationInput.Result.Valid -> viewModelScope.launch {
                repository.setSavedLocation(SavedLocation(f.name.trim(), result.location))
                repository.setLocationMode(LocationMode.SAVED)
                form.update { it.copy(errors = LocationInput.Errors(), message = "Saved. Using this location.") }
            }
        }
    }

    private fun SavedLocation.toForm() = SavedLocationForm(
        name = name,
        latitude = LocationInput.formatForInput(location.latitudeDeg),
        longitude = LocationInput.formatForInput(location.longitudeDeg),
        altitude = LocationInput.formatForInput(location.altitudeMeters, maxDecimals = 0),
    )

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                SettingsViewModel(app.container.settingsRepository)
            }
        }
    }
}
