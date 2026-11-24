package io.github.kevinnortier.isstracker.ui.passdetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.notify.PassKey
import io.github.kevinnortier.isstracker.notify.matchPass
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.PassPredictor
import io.github.kevinnortier.isstracker.orbit.SatelliteTracker
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One sample of the pass path for the sky plot. */
data class TrackPoint(val azimuthDeg: Double, val elevationDeg: Double, val visible: Boolean)

data class PassDetailsUiState(
    val noradId: Int,
    val satelliteName: String,
    /** Bright enough to see with the eye when sunlit (otherwise "visible" only means sunlit). */
    val bright: Boolean = true,
    /** The satellite's colour (null, i.e. grey, if it's no longer tracked). */
    val colorIndex: Int? = null,
    val loading: Boolean = true,
    /** Set when the pass can't be found (e.g. it's over, or no longer above the minimum elevation). */
    val notFound: Boolean = false,
    val pass: Pass? = null,
    val track: List<TrackPoint> = emptyList(),
    val reminderOn: Boolean = false,
    /** Where the satellite is now, if the pass is in progress. */
    val currentLook: LookAngles? = null,
    /**
     * True if Find in sky would aim at this pass: it's in progress, or it's the next one
     * (every earlier pass has ended).
     */
    val findInSkyTracksThis: Boolean = false,
    val now: Instant = Instant.EPOCH,
)

/** Details of one pass, identified by the satellite and its (approximate) rise time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PassDetailsViewModel(
    private val container: AppContainer,
    private val noradId: Int,
    riseMillis: Long,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class Computed(
        val pass: Pass?,
        val track: List<TrackPoint>,
        val predictor: PassPredictor?,
        /** Passes between "now" (when calculated) and this one; this pass is next once they have all ended. */
        val earlierPasses: List<Pass> = emptyList(),
    )

    private val key = PassKey(noradId, riseMillis)

    /** The tracked satellite (null if it's no longer tracked). */
    private val satellite = container.settingsRepository.trackedSatellites
        .map { list -> list.firstOrNull { it.noradId == noradId } }
        .distinctUntilChanged()

    private val computed = combine(
        container.tleRepository.latest(noradId).map { it?.tle }.distinctUntilChanged(),
        container.settingsRepository.settings
            .map { it.resolveObserver()?.location to it.minElevationDeg }
            .distinctUntilChanged(),
    ) { tle, observer -> tle to observer }
        .mapLatest { (tle, observer) ->
            val (location, minElevation) = observer
            if (tle == null || location == null) return@mapLatest null
            runCatching {
                val predictor = PassPredictor(tle, location, minElevation)
                val around = Instant.ofEpochMilli(key.riseMillis)
                val pass = matchPass(key, predictor.passes(around.minus(WINDOW), around.plus(WINDOW)))
                val earlier = pass?.let { p ->
                    val now = clock.instant()
                    if (p.rise.time.isAfter(now)) {
                        predictor.passes(now, p.rise.time).filter { it.rise.time.isBefore(p.rise.time.minusSeconds(60)) }
                    } else {
                        emptyList()
                    }
                }.orEmpty()
                Computed(pass, pass?.let { track(SatelliteTracker(tle, location), it) }.orEmpty(), predictor, earlier)
            }.getOrElse { Computed(null, emptyList(), null) }
        }
        .flowOn(Dispatchers.Default)
        .onStart { emit(null) }

    val uiState: StateFlow<PassDetailsUiState> = combine(
        computed,
        satellite,
        container.settingsRepository.selectedPasses,
        secondTicker(clock),
    ) { computed, satellite, selected, now ->
        val base = PassDetailsUiState(
            noradId = noradId,
            satelliteName = satellite?.displayName ?: "#$noradId",
            bright = satellite?.bright ?: false,
            colorIndex = satellite?.colorIndex,
            now = now,
        )
        when {
            computed == null -> base
            computed.pass == null -> base.copy(loading = false, notFound = true)
            else -> base.copy(
                loading = false,
                pass = computed.pass,
                track = computed.track,
                reminderOn = selected.any { it.noradId == noradId && matchPass(it, listOf(computed.pass)) != null },
                findInSkyTracksThis = computed.pass.set.time.isAfter(now) &&
                    computed.earlierPasses.none { it.set.time.isAfter(now) },
                currentLook = if (computed.pass.isInProgress(now)) {
                    runCatching { computed.predictor?.lookAngles(now) }.getOrNull()
                } else {
                    null
                },
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PassDetailsUiState(noradId = noradId, satelliteName = ""),
    )

    init {
        // Make sure the cached TLE is loaded even if the app was restored straight into this screen.
        viewModelScope.launch { container.tleRepository.getCached(noradId) }
    }

    fun toggleReminder() {
        val pass = uiState.value.pass ?: return
        viewModelScope.launch { container.reminderScheduler.toggle(noradId, pass) }
    }

    /** The path from rise to set, sampled every few seconds, with the visible-to-the-eye flag. */
    private fun track(tracker: SatelliteTracker, pass: Pass): List<TrackPoint> {
        val points = mutableListOf<TrackPoint>()
        var t = pass.rise.time
        while (!t.isAfter(pass.set.time)) {
            val o = tracker.observe(t)
            points += TrackPoint(o.look.azimuthDeg, o.look.elevationDeg, o.visible)
            t = t.plus(TRACK_STEP)
        }
        val end = tracker.observe(pass.set.time)
        points += TrackPoint(end.look.azimuthDeg, end.look.elevationDeg, end.visible)
        return points
    }

    companion object {
        private val WINDOW: Duration = Duration.ofHours(2)
        private val TRACK_STEP: Duration = Duration.ofSeconds(5)

        fun factory(noradId: Int, riseMillis: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                return PassDetailsViewModel(app.container, noradId, riseMillis) as T
            }
        }
    }
}
