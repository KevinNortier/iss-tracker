package io.github.kevinnortier.isstracker.ui.aim

import android.hardware.GeomagneticField
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kevinnortier.isstracker.AppContainer
import io.github.kevinnortier.isstracker.IssTrackerApp
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.orbit.PassPredictor
import io.github.kevinnortier.isstracker.orbit.Sun
import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.sensors.AimSolution
import io.github.kevinnortier.isstracker.sensors.AzEl
import io.github.kevinnortier.isstracker.sensors.CompassAccuracy
import io.github.kevinnortier.isstracker.sensors.HeadingSource
import io.github.kevinnortier.isstracker.sensors.OrientationReading
import io.github.kevinnortier.isstracker.sensors.Pointing
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import io.github.kevinnortier.isstracker.ui.secondTicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

/** How the gyro heading was aligned to north. */
enum class AlignMethod { COMPASS_AUTO, COMPASS, SUN, SATELLITE }

/** Gyro-mode heading alignment: true azimuth = gyro azimuth + [offsetDeg]. */
data class HeadingAlignment(
    val offsetDeg: Double,
    val method: AlignMethod,
    val time: Instant,
    /** The satellite pointed at, for [AlignMethod.SATELLITE]. */
    val satelliteName: String? = null,
)

data class AimUiState(
    val noradId: Int,
    val satelliteName: String,
    /** Bright enough to see, so it can be used to align the gyro heading. */
    val bright: Boolean = false,
    /** Tracked satellites to choose from. */
    val tracked: List<Satellite> = emptyList(),
    val sensorAvailable: Boolean = true,
    val axis: PointingAxis = PointingAxis.BACK,
    val headingSource: HeadingSource = HeadingSource.COMPASS,
    /** True when gyro mode was chosen but this phone has no gyro-only rotation sensor. */
    val gyroUnavailable: Boolean = false,
    /** Gyro mode only: how and when the heading was aligned (null while waiting for the first alignment). */
    val alignment: HeadingAlignment? = null,
    /** Result of the last "align" tap that couldn't be done, e.g. pointing straight up. */
    val alignMessage: String? = null,
    /** Where the sun is, and whether it's a good alignment target right now. */
    val sun: AzEl? = null,
    val canAlignToSun: Boolean = false,
    val canAlignToSatellite: Boolean = false,
    /** Why aiming isn't possible (no location / orbit data), if so. */
    val problem: String? = null,
    val target: AimTarget? = null,
    /** Null until the first sensor reading arrives (and, in gyro mode, the heading is aligned). */
    val aim: AimSolution? = null,
    val needsCalibration: Boolean = false,
    val compassAccuracy: CompassAccuracy = CompassAccuracy.UNKNOWN,
    val magneticFieldMicroTesla: Double? = null,
    val magneticInterference: Boolean = false,
    val declinationDeg: Double = 0.0,
    val now: Instant = Instant.EPOCH,
) {
    val onTarget: Boolean get() = aim != null && target != null && aim.separationDeg <= ON_TARGET_DEG

    companion object {
        /** Within this many degrees counts as "on target". */
        const val ON_TARGET_DEG = 5.0

        /** Aligning to the sun or a satellite needs it clearly above the horizon but not overhead. */
        val ALIGN_ELEVATION_RANGE = 2.0..75.0
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AimViewModel(
    private val container: AppContainer,
    noradId: Int,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {

    private data class Geometry(
        val predictor: PassPredictor?,
        val passes: List<Pass>,
        val location: GeodeticLocation?,
        val declinationDeg: Double,
        val problem: String?,
    )

    private val settings = container.settingsRepository.settings
    private val sensor = container.orientationSensor

    /** The satellite being aimed at; can be switched on the screen. */
    private val selectedId = MutableStateFlow(noradId)
    private val tracked = container.settingsRepository.trackedSatellites

    /** The selected satellite (a placeholder if it's not tracked any more). */
    private val selected: Flow<Satellite> = combine(selectedId, tracked) { id, list ->
        list.firstOrNull { it.noradId == id } ?: Satellite(id, "#$id")
    }.distinctUntilChanged()

    private val inputs: Flow<Pair<Tle?, Pair<GeodeticLocation?, Double>>> = combine(
        selectedId.flatMapLatest { id -> container.tleRepository.latest(id) }.map { it?.tle }.distinctUntilChanged(),
        settings.map { it.resolveObserver()?.location to it.minElevationDeg }.distinctUntilChanged(),
    ) { tle, observer -> tle to observer }

    /** Predictor, the next days of passes and the magnetic declination; recalculated when inputs change. */
    private val geometry: Flow<Geometry> = inputs.mapLatest { (tle, observer) ->
        val (location, minElevation) = observer
        when {
            location == null -> Geometry(null, emptyList(), null, 0.0, "Choose a location first (Settings).")
            tle == null -> Geometry(null, emptyList(), location, 0.0, "Waiting for orbit data…")
            else -> try {
                val predictor = PassPredictor(tle, location, minElevation)
                val now = clock.instant()
                val passes = predictor.passes(now, now.plus(Duration.ofDays(2)))
                Geometry(predictor, passes, location, declination(location, now), null)
            } catch (e: RuntimeException) {
                Geometry(null, emptyList(), location, 0.0, "Could not calculate the position: ${e.message}")
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Gyro alignment for the current sensor session. The gyro's "north" resets whenever the sensor restarts. */
    private val alignment = MutableStateFlow<HeadingAlignment?>(null)
    private val alignMessage = MutableStateFlow<String?>(null)

    private val headingSource = settings.map { it.headingSource }.distinctUntilChanged()

    private val readings: Flow<OrientationReading?> = headingSource.flatMapLatest { source ->
        sensor.readings(source)
            .map<OrientationReading, OrientationReading?> { it }
            .onStart {
                alignment.value = null // a new sensor session: the old alignment no longer applies
                alignMessage.value = null
                emit(null)
            }
    }

    // The latest values, for the align actions.
    private var lastReading: OrientationReading? = null
    private var lastState: AimUiState? = null

    private data class Setup(
        val axis: PointingAxis,
        val source: HeadingSource,
        val satellite: Satellite,
        val tracked: List<Satellite>,
    )

    private val setup: Flow<Setup> = combine(
        settings.map { it.pointingAxis }.distinctUntilChanged(),
        headingSource,
        selected,
        tracked,
        ::Setup,
    )

    val uiState: StateFlow<AimUiState> = combine(
        setup,
        geometry,
        readings,
        combine(alignment, alignMessage, ::Pair),
        secondTicker(clock), // keeps the target moving even without sensor readings
    ) { (axis, source, satellite, trackedList), geo, reading, (align, message), _ ->
        val now = clock.instant()
        lastReading = reading
        val target = geo.predictor?.let { p ->
            runCatching { chooseTarget(p.lookAngles(now), geo.passes, now) }.getOrNull()
        }

        // Gyro mode starts aligned from the compass as soon as both readings are there.
        var currentAlignment = align
        if (reading?.source == HeadingSource.GYRO && currentAlignment == null && reading.compassMatrix != null) {
            currentAlignment = HeadingAlignment(
                Pointing.gyroOffsetFromCompass(reading.compassMatrix, reading.rotationMatrix, geo.declinationDeg),
                AlignMethod.COMPASS_AUTO,
                now,
            )
            alignment.value = currentAlignment
        }

        val aim = when {
            reading == null || target == null -> null
            reading.source == HeadingSource.COMPASS ->
                Pointing.solve(reading.rotationMatrix, axis, target.direction, geo.declinationDeg)
            currentAlignment != null -> Pointing.solve(
                Pointing.rotateAboutVertical(reading.rotationMatrix, currentAlignment.offsetDeg),
                axis,
                target.direction,
                declinationDeg = 0.0, // the aligned gyro frame already uses true north
            )
            else -> null
        }

        val sun = geo.location?.let { location ->
            val (az, el) = Sun.lookAngles(location, now)
            AzEl(az, el)
        }
        AimUiState(
            noradId = satellite.noradId,
            satelliteName = satellite.displayName,
            bright = satellite.bright,
            tracked = trackedList,
            sensorAvailable = sensor.isAvailable,
            axis = axis,
            headingSource = source,
            gyroUnavailable = source == HeadingSource.GYRO && !sensor.isGyroAvailable,
            alignment = if (reading?.source == HeadingSource.GYRO) currentAlignment else null,
            alignMessage = message,
            sun = sun,
            canAlignToSun = sun != null && sun.elevationDeg in AimUiState.ALIGN_ELEVATION_RANGE,
            canAlignToSatellite = satellite.bright && target is AimTarget.Live &&
                target.direction.elevationDeg in AimUiState.ALIGN_ELEVATION_RANGE,
            problem = geo.problem,
            target = target,
            aim = aim,
            needsCalibration = reading?.needsCalibration ?: false,
            compassAccuracy = reading?.compassAccuracy ?: CompassAccuracy.UNKNOWN,
            magneticFieldMicroTesla = reading?.magneticFieldMicroTesla,
            magneticInterference = reading?.magneticInterference ?: false,
            declinationDeg = geo.declinationDeg,
            now = now,
        ).also { lastState = it }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(2_000), // stops the sensors shortly after leaving the screen
        AimUiState(noradId = noradId, satelliteName = ""),
    )

    init {
        // Make sure the cached orbit data is loaded (and downloaded if missing) for the selected satellite.
        viewModelScope.launch {
            selectedId.collect { id -> runCatching { container.tleRepository.getTle(id) } }
        }
    }

    /** Aims at another tracked satellite. The gyro alignment stays valid (it's about the phone, not the target). */
    fun selectSatellite(noradId: Int) {
        selectedId.value = noradId
    }

    fun setAxis(axis: PointingAxis) {
        viewModelScope.launch { container.settingsRepository.setPointingAxis(axis) }
    }

    fun setHeadingSource(source: HeadingSource) {
        viewModelScope.launch { container.settingsRepository.setHeadingSource(source) }
    }

    /** Gyro mode: take north from the compass again (e.g. away from magnets). */
    fun alignFromCompass() {
        val reading = lastReading ?: return
        val compass = reading.compassMatrix ?: return
        val declination = lastState?.declinationDeg ?: 0.0
        setAlignment(Pointing.gyroOffsetFromCompass(compass, reading.rotationMatrix, declination), AlignMethod.COMPASS)
    }

    /** Gyro mode: the user points the phone at the sun; its known azimuth sets north. */
    fun alignToSun() {
        val sun = lastState?.sun ?: return
        alignToDirection(sun, AlignMethod.SUN)
    }

    /** Gyro mode: the user points the phone at the (bright, visible) satellite; its known azimuth sets north. */
    fun alignToSatellite() {
        val state = lastState ?: return
        val target = state.target as? AimTarget.Live ?: return
        alignToDirection(target.direction, AlignMethod.SATELLITE, state.satelliteName)
    }

    private fun alignToDirection(direction: AzEl, method: AlignMethod, satelliteName: String? = null) {
        val reading = lastReading ?: return
        val axis = lastState?.axis ?: PointingAxis.BACK
        val offset = Pointing.gyroOffsetFromTarget(reading.rotationMatrix, axis, direction.azimuthDeg)
        if (offset == null) {
            alignMessage.value = "Can't align while pointing almost straight up or down."
        } else {
            setAlignment(offset, method, satelliteName)
        }
    }

    private fun setAlignment(offset: Double, method: AlignMethod, satelliteName: String? = null) {
        alignment.value = HeadingAlignment(offset, method, clock.instant(), satelliteName)
        alignMessage.value = null
    }

    private fun declination(location: GeodeticLocation, now: Instant): Double = GeomagneticField(
        location.latitudeDeg.toFloat(),
        location.longitudeDeg.toFloat(),
        location.altitudeMeters.toFloat(),
        now.toEpochMilli(),
    ).declination.toDouble()

    companion object {
        fun factory(noradId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as IssTrackerApp
                AimViewModel(app.container, noradId)
            }
        }
    }
}
