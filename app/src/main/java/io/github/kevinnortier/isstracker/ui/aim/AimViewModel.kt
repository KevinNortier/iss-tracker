package io.github.kevinnortier.isstracker.ui.aim

import android.hardware.GeomagneticField
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.PassPredictor
import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.sensors.AimSolution
import io.github.kevinnortier.isstracker.sensors.AzEl
import io.github.kevinnortier.isstracker.sensors.CompassAccuracy
import io.github.kevinnortier.isstracker.sensors.OrientationReading
import io.github.kevinnortier.isstracker.sensors.Pointing
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** What the aiming screen points at. */
sealed interface AimTarget {
    val direction: AzEl

    /** The satellite is above the horizon: aim at it. */
    data class Live(override val direction: AzEl, val look: LookAngles, val pass: Pass?) : AimTarget

    /** Below the horizon: aim at where the next pass rises, so the antenna is ready. */
    data class NextRise(override val direction: AzEl, val pass: Pass) : AimTarget
}

/**
 * Picks the target: the satellite itself while it is above the horizon, otherwise the rise point
 * of the next pass. Null if it is below the horizon and no pass is known.
 */
fun chooseTarget(look: LookAngles, passes: List<Pass>, now: Instant): AimTarget? {
    val inProgress = passes.firstOrNull { it.isInProgress(now) }
    if (look.elevationDeg > 0.0 || inProgress != null) {
        return AimTarget.Live(AzEl(look.azimuthDeg, look.elevationDeg), look, inProgress)
    }
    val next = passes.firstOrNull { it.rise.time.isAfter(now) } ?: return null
    return AimTarget.NextRise(AzEl(next.rise.azimuthDeg, next.rise.elevationDeg), next)
}

data class AimUiState(
    val satelliteName: String,
    val sensorAvailable: Boolean = true,
    val axis: PointingAxis = PointingAxis.BACK,
    /** Why aiming isn't possible (no location / orbit data), if so. */
    val problem: String? = null,
    val target: AimTarget? = null,
    /** Null until the first sensor reading arrives. */
    val aim: AimSolution? = null,
    val needsCalibration: Boolean = false,
    val compassAccuracy: CompassAccuracy = CompassAccuracy.UNKNOWN,
    val declinationDeg: Double = 0.0,
    val now: Instant = Instant.EPOCH,
) {
    val onTarget: Boolean get() = aim != null && target != null && aim.separationDeg <= ON_TARGET_DEG

    companion object {
        /** Within this many degrees counts as "on target". */
        const val ON_TARGET_DEG = 5.0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AimViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class Geometry(
        val predictor: PassPredictor?,
        val passes: List<Pass>,
        val declinationDeg: Double,
        val problem: String?,
    )

    private val settings = container.settingsRepository.settings

    private val inputs: Flow<Pair<Tle?, Pair<GeodeticLocation?, Double>>> = combine(
        container.tleRepository.latest(container.satellite.noradId).map { it?.tle }.distinctUntilChanged(),
        settings.map { it.resolveObserver()?.location to it.minElevationDeg }.distinctUntilChanged(),
    ) { tle, observer -> tle to observer }

    /** Predictor, the next day of passes and the magnetic declination; recalculated when inputs change. */
    private val geometry: Flow<Geometry> = inputs.mapLatest { (tle, observer) ->
        val (location, minElevation) = observer
        when {
            location == null -> Geometry(null, emptyList(), 0.0, "Choose a location first (Settings).")
            tle == null -> Geometry(null, emptyList(), 0.0, "Waiting for orbit data…")
            else -> try {
                val predictor = PassPredictor(tle, location, minElevation)
                val now = clock.instant()
                val passes = predictor.passes(now, now.plus(Duration.ofDays(2)))
                Geometry(predictor, passes, declination(location, now), null)
            } catch (e: RuntimeException) {
                Geometry(null, emptyList(), 0.0, "Could not calculate the position: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.Default)

    private val readings: Flow<OrientationReading?> = container.orientationSensor.readings()
        .map<OrientationReading, OrientationReading?> { it }
        .onStart { emit(null) }

    val uiState: StateFlow<AimUiState> = combine(
        settings.map { it.pointingAxis }.distinctUntilChanged(),
        geometry,
        readings,
        secondTicker(clock), // keeps the target moving even without sensor readings
    ) { axis, geo, reading, _ ->
        val now = clock.instant()
        val target = geo.predictor?.let { p ->
            runCatching { chooseTarget(p.lookAngles(now), geo.passes, now) }.getOrNull()
        }
        AimUiState(
            satelliteName = container.satellite.displayName,
            sensorAvailable = container.orientationSensor.isAvailable,
            axis = axis,
            problem = geo.problem,
            target = target,
            aim = if (reading != null && target != null) {
                Pointing.solve(reading.rotationMatrix, axis, target.direction, geo.declinationDeg)
            } else {
                null
            },
            needsCalibration = reading?.needsCalibration ?: false,
            compassAccuracy = reading?.compassAccuracy ?: CompassAccuracy.UNKNOWN,
            declinationDeg = geo.declinationDeg,
            now = now,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(2_000), // stops the sensor shortly after leaving the screen
        AimUiState(satelliteName = container.satellite.displayName),
    )

    fun setAxis(axis: PointingAxis) {
        viewModelScope.launch { container.settingsRepository.setPointingAxis(axis) }
    }

    private fun declination(location: GeodeticLocation, now: Instant): Double = GeomagneticField(
        location.latitudeDeg.toFloat(),
        location.longitudeDeg.toFloat(),
        location.altitudeMeters.toFloat(),
        now.toEpochMilli(),
    ).declination.toDouble()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                AimViewModel(app.container)
            }
        }
    }
}
