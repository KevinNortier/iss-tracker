package io.github.kevinnortier.isstracker.ui.passes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.notify.PassKey
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A pass of a particular satellite. */
data class SatPass(val satellite: Satellite, val pass: Pass) {
    val noradId: Int get() = satellite.noradId

    /** Visible to the naked eye: sunlit in a dark sky AND bright enough to see (★). */
    val visibleToEye: Boolean get() = satellite.bright && pass.visible

    /** Sunlit while the sky is dark, but probably too faint to see with the eye. */
    val sunlitOnly: Boolean get() = !satellite.bright && pass.visible

    /** Stable key for lists (and the reminder identity). */
    val key: PassKey get() = PassKey.of(noradId, pass)
}

/** All passes of all satellites in one list, in rise-time order. */
fun mergePasses(perSatellite: List<Pair<Satellite, List<Pass>>>): List<SatPass> =
    perSatellite.flatMap { (s, passes) -> passes.map { SatPass(s, it) } }.sortedBy { it.pass.rise.time }

/** The satellites shown: the filter's tracked ones, or all tracked when the filter is empty / stale. */
fun effectiveFilter(filter: Set<Int>, tracked: List<Satellite>): Set<Int> {
    val trackedIds = tracked.map { it.noradId }.toSet()
    return (filter intersect trackedIds).ifEmpty { trackedIds }
}

/** Only the passes visible to the naked eye when [visibleOnly], otherwise all of them. */
fun filterVisible(passes: List<SatPass>, visibleOnly: Boolean): List<SatPass> =
    if (visibleOnly) passes.filter { it.visibleToEye } else passes

/** Passes that haven't ended at a moment, the ones in progress, and the next one to start. */
data class PassSelection(val upcoming: List<SatPass>, val current: List<SatPass>, val next: SatPass?)

/** Splits [passes] (in rise-time order) at [now]. Several satellites can be passing at once. */
fun selectPasses(passes: List<SatPass>, now: Instant): PassSelection {
    val upcoming = passes.filter { it.pass.set.time.isAfter(now) }
    return PassSelection(
        upcoming = upcoming,
        current = upcoming.filter { it.pass.isInProgress(now) },
        next = upcoming.firstOrNull { it.pass.rise.time.isAfter(now) },
    )
}

/** "ISS passes" when only one satellite is tracked, otherwise "Passes". */
fun passesTitle(tracked: List<Satellite>): String =
    tracked.singleOrNull()?.let { "${it.displayName} passes" } ?: "Passes"

data class PassesUiState(
    val title: String = "Passes",
    /** True until the first prediction is ready (or it is clear there can be none). */
    val loading: Boolean = true,
    /** Why there are no predictions at all (e.g. no orbit data for any satellite), if that's the case. */
    val error: String? = null,
    val hasObserver: Boolean = true,
    val tracked: List<Satellite> = emptyList(),
    /** NORAD IDs currently shown (after the filter). */
    val shown: Set<Int> = emptySet(),
    /** True when only passes visible to the naked eye are shown. */
    val visibleOnly: Boolean = false,
    /** Passes of the shown satellites that have not ended yet, in time order. */
    val passes: List<SatPass> = emptyList(),
    /** Passes in progress right now (the first is shown in the card). */
    val current: List<SatPass> = emptyList(),
    /** The next pass that hasn't started yet. */
    val next: SatPass? = null,
    /** Where the satellite of the first current pass is right now. */
    val currentLook: LookAngles? = null,
    val minElevationDeg: Double = 0.0,
    /** Oldest orbit data among the shown satellites (for the footer). */
    val oldestTleEpoch: Instant? = null,
    /** Shown satellites without orbit data, or whose passes can't be calculated. */
    val withoutData: List<Satellite> = emptyList(),
    /** Passes (from [passes]) that have a reminder turned on. */
    val reminders: Set<SatPass> = emptySet(),
    val now: Instant = Instant.EPOCH,
) {
    val noSatellites: Boolean get() = !loading && tracked.isEmpty()
    val showSatelliteNames: Boolean get() = tracked.size > 1
    fun hasReminder(pass: SatPass): Boolean = pass in reminders
}

/** Shows upcoming passes of all tracked satellites and the current/next pass. */
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

    private data class PredictionSettings(val location: GeodeticLocation, val minElevationDeg: Double)

    /** One satellite's predictor and passes, or null when it has no (usable) orbit data. */
    private data class SatPrediction(val satellite: Satellite, val predictor: PassPredictor?, val passes: List<Pass>)

    private sealed interface Prediction {
        data object Waiting : Prediction
        data object NoObserver : Prediction
        data class Ready(val perSatellite: List<SatPrediction>, val minElevationDeg: Double) : Prediction
    }

    private val tracked = container.settingsRepository.trackedSatellites

    /** NORAD IDs whose orbit data couldn't be downloaded (and isn't cached). */
    private val downloadFailures = MutableStateFlow<Set<Int>>(emptySet())

    /** Each tracked satellite with its current TLE (null until loaded). */
    private val tles: Flow<List<Pair<Satellite, Tle?>>> = tracked.flatMapLatest { satellites ->
        if (satellites.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(satellites.map { s -> container.tleRepository.latest(s.noradId).map { s to it?.tle } }) { it.toList() }
        }
    }.distinctUntilChanged()

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

    private val prediction: Flow<Prediction> = combine(tles, predictionSettings, recalculateTicker) { tles, settings, _ ->
        tles to settings
    }
        .mapLatest { (tles, settings) ->
            if (settings == null) return@mapLatest Prediction.NoObserver
            val now = clock.instant()
            val until = now.plus(PREDICTION_WINDOW)
            Prediction.Ready(
                tles.map { (satellite, tle) ->
                    if (tle == null) {
                        SatPrediction(satellite, null, emptyList())
                    } else {
                        try {
                            val predictor = PassPredictor(tle, settings.location, settings.minElevationDeg)
                            SatPrediction(satellite, predictor, predictor.passes(now, until))
                        } catch (e: RuntimeException) {
                            SatPrediction(satellite, null, emptyList()) // e.g. a deep-space orbit
                        }
                    }
                },
                settings.minElevationDeg,
            )
        }
        .flowOn(Dispatchers.Default)
        .onStart { emit(Prediction.Waiting) }

    private data class ListInputs(
        val filter: Set<Int>,
        val visibleOnly: Boolean,
        val selected: Set<PassKey>,
        val failures: Set<Int>,
    )

    private val listInputs = combine(
        container.settingsRepository.passFilter,
        container.settingsRepository.passVisibleOnly,
        container.settingsRepository.selectedPasses,
        downloadFailures,
    ) { filter, visibleOnly, selected, failures -> ListInputs(filter, visibleOnly, selected, failures) }

    val uiState: StateFlow<PassesUiState> = combine(
        prediction,
        tracked,
        tles,
        listInputs,
        secondTicker(clock),
    ) { prediction, tracked, tles, (filter, visibleOnly, selected, failures), now ->
        val shown = effectiveFilter(filter, tracked)
        val base = PassesUiState(
            title = passesTitle(tracked),
            loading = false,
            tracked = tracked,
            shown = shown,
            visibleOnly = visibleOnly,
            oldestTleEpoch = tles.filter { it.first.noradId in shown }.mapNotNull { it.second?.epoch }.minOrNull(),
            now = now,
        )
        when (prediction) {
            Prediction.Waiting -> base.copy(loading = tracked.isNotEmpty())
            Prediction.NoObserver -> base.copy(hasObserver = false)
            is Prediction.Ready -> {
                val shownPredictions = prediction.perSatellite.filter { it.satellite.noradId in shown }
                // A satellite without a TLE whose download hasn't failed is still loading.
                val noTle = tles.filter { it.second == null }.map { it.first.noradId }.toSet()
                fun stillLoading(id: Int) = id in noTle && id !in failures
                val waiting = shownPredictions.any { stillLoading(it.satellite.noradId) }
                val withoutData = shownPredictions
                    .filter { it.predictor == null && !stillLoading(it.satellite.noradId) }
                    .map { it.satellite }
                val merged = filterVisible(mergePasses(shownPredictions.map { it.satellite to it.passes }), visibleOnly)
                val selection = selectPasses(merged, now)
                val firstCurrent = selection.current.firstOrNull()
                base.copy(
                    loading = waiting && merged.isEmpty(),
                    error = if (shownPredictions.isNotEmpty() && shownPredictions.all { it.predictor == null } && !waiting) {
                        "No orbit data available. Connect to the internet and try again."
                    } else {
                        null
                    },
                    passes = selection.upcoming,
                    current = selection.current,
                    next = selection.next,
                    currentLook = firstCurrent?.let { cp ->
                        prediction.perSatellite.firstOrNull { it.satellite.noradId == cp.noradId }
                            ?.predictor?.let { runCatching { it.lookAngles(now) }.getOrNull() }
                    },
                    minElevationDeg = prediction.minElevationDeg,
                    withoutData = withoutData,
                    reminders = selection.upcoming.filter { sp ->
                        selected.any { it.noradId == sp.noradId && matchPass(it, listOf(sp.pass)) != null }
                    }.toSet(),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PassesUiState())

    /** Turns the reminder for [pass] on or off. */
    fun toggleReminder(pass: SatPass) {
        viewModelScope.launch { container.reminderScheduler.toggle(pass.noradId, pass.pass) }
    }

    /** Shows or hides a satellite in the list; "all" when [noradId] is null. */
    fun toggleFilter(noradId: Int?) {
        viewModelScope.launch {
            val state = uiState.value
            val newFilter = when {
                noradId == null -> emptySet()
                // Tapping a satellite while showing all: show only that one.
                state.shown.size == state.tracked.size -> setOf(noradId)
                noradId in state.shown -> state.shown - noradId
                else -> state.shown + noradId
            }
            // Showing everything (or nothing) is stored as "all".
            val all = state.tracked.map { it.noradId }.toSet()
            container.settingsRepository.setPassFilter(if (newFilter.isEmpty() || newFilter == all) emptySet() else newFilter)
        }
    }

    /** Shows only passes visible to the naked eye, or all passes again. */
    fun toggleVisibleOnly() {
        viewModelScope.launch {
            container.settingsRepository.setPassVisibleOnly(!uiState.value.visibleOnly)
        }
    }

    init {
        // Load (and if needed download) the orbit data of every tracked satellite, also when the list changes.
        viewModelScope.launch {
            tracked.collectLatest { satellites ->
                for (s in satellites) {
                    try {
                        container.tleRepository.getTle(s.noradId)
                        downloadFailures.update { it - s.noradId }
                    } catch (e: IOException) {
                        downloadFailures.update { it + s.noradId }
                    }
                }
            }
        }
    }
}
