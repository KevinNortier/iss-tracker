package io.github.kevinnortier.isstracker.ui.passes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.notify.matchPass
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.PassPredictor
import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class PassesUiState(
    val satelliteName: String,
    /** True until the first prediction is ready (or it is clear there can be none). */
    val loading: Boolean = true,
    /** Why there are no predictions (no orbit data, calculation error), if that's the case. */
    val error: String? = null,
    val hasObserver: Boolean = true,
    /** Passes that have not ended yet, in time order. */
    val passes: List<Pass> = emptyList(),
    /** The pass in progress right now, if any. */
    val current: Pass? = null,
    /** The next pass that hasn't started yet. */
    val next: Pass? = null,
    /** Where the satellite is right now (only during a pass). */
    val currentLook: LookAngles? = null,
    val minElevationDeg: Double = 0.0,
    val tleEpoch: Instant? = null,
    /** Passes (from [passes]) that have a reminder turned on. */
    val reminders: Set<Pass> = emptySet(),
    val now: Instant = Instant.EPOCH,
) {
    fun hasReminder(pass: Pass): Boolean = pass in reminders
}

/** Passes that haven't ended at a moment, and which of them is in progress / next. */
data class PassSelection(val upcoming: List<Pass>, val current: Pass?, val next: Pass?)

/** Splits [passes] (in time order) into upcoming, the one in progress at [now], and the next one to start. */
fun selectPasses(passes: List<Pass>, now: Instant): PassSelection {
    val upcoming = passes.filter { it.set.time.isAfter(now) }
    return PassSelection(
        upcoming = upcoming,
        current = upcoming.firstOrNull { it.isInProgress(now) },
        next = upcoming.firstOrNull { it.rise.time.isAfter(now) },
    )
}

/** Shows upcoming passes and the current/next pass. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassesViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    companion object {
        val PREDICTION_WINDOW: Duration = Duration.ofDays(10)
        val RECALCULATE_EVERY: Duration = Duration.ofHours(1)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                PassesViewModel(app.container)
            }
        }
    }

    private data class Inputs(val tle: Tle?, val settings: PredictionSettings?, val tick: Long)
    private data class PredictionSettings(
        val location: GeodeticLocation,
        val minElevationDeg: Double,
    )

    private sealed interface Prediction {
        data object Waiting : Prediction
        data object NoObserver : Prediction
        data class Ready(val predictor: PassPredictor, val passes: List<Pass>, val minElevationDeg: Double) : Prediction
        data class Failed(val message: String) : Prediction
    }

    private val tleFailure = MutableStateFlow<String?>(null)
    private val tle = container.tleRepository.latest(container.satellite.noradId)

    private val predictionSettings: Flow<PredictionSettings?> = container.settingsRepository.settings
        .map { s -> s.resolveObserver()?.let { PredictionSettings(it.location, s.minElevationDeg) } }
        .distinctUntilChanged()

    /** Ticks once per [RECALCULATE_EVERY] so the 10-day window moves forward. */
    private val recalculateTicker: Flow<Long> = flow {
        var n = 0L
        while (true) {
            emit(n++)
            delay(RECALCULATE_EVERY.toMillis())
        }
    }

    private val prediction: Flow<Prediction> = combine(
        tle.map { it?.tle }.distinctUntilChanged(),
        predictionSettings,
        recalculateTicker,
    ) { tle, settings, tick -> Inputs(tle, settings, tick) }
        .mapLatest { inputs ->
            when {
                inputs.settings == null -> Prediction.NoObserver
                inputs.tle == null -> Prediction.Waiting
                else -> try {
                    val predictor = PassPredictor(inputs.tle, inputs.settings.location, inputs.settings.minElevationDeg)
                    val now = clock.instant()
                    Prediction.Ready(predictor, predictor.passes(now, now.plus(PREDICTION_WINDOW)), inputs.settings.minElevationDeg)
                } catch (e: RuntimeException) {
                    Prediction.Failed(e.message ?: e.toString())
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .onStart { emit(Prediction.Waiting) }

    private val selectedPasses = container.settingsRepository.selectedPasses

    val uiState: StateFlow<PassesUiState> = combine(
        prediction, tle, tleFailure, selectedPasses, secondTicker(clock),
    ) { prediction, cached, tleFailure, selected, now ->
        val base = PassesUiState(satelliteName = container.satellite.displayName, tleEpoch = cached?.tle?.epoch, now = now)
        when (prediction) {
            Prediction.Waiting -> if (tleFailure != null && cached == null) {
                base.copy(loading = false, error = tleFailure)
            } else {
                base
            }
            Prediction.NoObserver -> base.copy(loading = false, hasObserver = false)
            is Prediction.Failed -> base.copy(loading = false, error = "Could not calculate passes: ${prediction.message}")
            is Prediction.Ready -> {
                val selection = selectPasses(prediction.passes, now)
                base.copy(
                    loading = false,
                    passes = selection.upcoming,
                    current = selection.current,
                    next = selection.next,
                    currentLook = selection.current?.let {
                        runCatching { prediction.predictor.lookAngles(now) }.getOrNull()
                    },
                    minElevationDeg = prediction.minElevationDeg,
                    reminders = selected.mapNotNull { matchPass(it, selection.upcoming) }.toSet(),
                )
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PassesUiState(satelliteName = container.satellite.displayName),
    )

    /** Turns the reminder for [pass] on or off. */
    fun toggleReminder(pass: Pass) {
        viewModelScope.launch { container.reminderScheduler.toggle(pass) }
    }

    init {
        viewModelScope.launch {
            try {
                container.tleRepository.getTle(container.satellite.noradId)
                tleFailure.value = null
            } catch (e: IOException) {
                tleFailure.value = e.message ?: "Could not load orbit data"
            }
        }
    }
}
