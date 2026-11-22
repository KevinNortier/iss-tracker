package io.github.kevinnortier.isstracker.ui.aim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.sensors.AimSolution
import io.github.kevinnortier.isstracker.sensors.CompassAccuracy
import io.github.kevinnortier.isstracker.sensors.HeadingSource
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.SatelliteDot
import java.time.Duration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Degrees from the centre to the outer ring. Targets further away are pinned to the edge. */
private const val RING_RANGE_DEG = 45.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AimRoute(
    noradId: Int,
    onBack: () -> Unit,
    viewModel: AimViewModel = viewModel(key = "aim-$noradId", factory = AimViewModel.factory(noradId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    KeepScreenOnInPortrait()
    LockOnFeedback(state.onTarget)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Find in sky") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.tracked.size > 1) SatelliteSelector(state.tracked, state.noradId, viewModel::selectSatellite)
            AxisSelector(state.axis, viewModel::setAxis)
            HeadingSelector(state.headingSource, viewModel::setHeadingSource)
            when {
                !state.sensorAvailable -> Text(
                    "This phone has no orientation sensor, so aiming isn't possible.",
                    color = MaterialTheme.colorScheme.error,
                )
                state.problem != null -> Text(state.problem!!)
                else -> {
                    TargetBanner(state)
                    AimRing(state)
                    Guidance(state)
                    if (state.headingSource == HeadingSource.GYRO && !state.gyroUnavailable) {
                        GyroAlignmentCard(
                            state = state,
                            onAlignToSun = viewModel::alignToSun,
                            onAlignToSatellite = viewModel::alignToSatellite,
                            onAlignFromCompass = viewModel::alignFromCompass,
                        )
                    } else {
                        if (state.gyroUnavailable) {
                            Text(
                                "This phone has no gyro-only rotation sensor, so the compass is used.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.magneticInterference) InterferenceWarning(state.magneticFieldMicroTesla)
                        if (state.needsCalibration) CalibrationHint()
                    }
                    Numbers(state)
                }
            }
        }
    }
}

@Composable
private fun SatelliteSelector(tracked: List<Satellite>, selectedId: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Satellite", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tracked.forEach { s ->
                FilterChip(
                    selected = s.noradId == selectedId,
                    onClick = { onSelect(s.noradId) },
                    label = { Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { SatelliteDot(s.noradId, size = 8.dp) },
                )
            }
        }
    }
}

@Composable
private fun AxisSelector(axis: PointingAxis, onChange: (PointingAxis) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Point with", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = axis == PointingAxis.BACK,
                onClick = { onChange(PointingAxis.BACK) },
                label = { Text("Back of phone") },
            )
            FilterChip(
                selected = axis == PointingAxis.TOP,
                onClick = { onChange(PointingAxis.TOP) },
                label = { Text("Top of phone") },
            )
        }
    }
}

@Composable
private fun HeadingSelector(source: HeadingSource, onChange: (HeadingSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Heading from", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == HeadingSource.COMPASS,
                onClick = { onChange(HeadingSource.COMPASS) },
                label = { Text("Compass") },
            )
            FilterChip(
                selected = source == HeadingSource.GYRO,
                onClick = { onChange(HeadingSource.GYRO) },
                label = { Text("Gyro") },
            )
        }
    }
}

/** Gyro mode: how north was set, and buttons to set it again. */
@Composable
private fun GyroAlignmentCard(
    state: AimUiState,
    onAlignToSun: () -> Unit,
    onAlignToSatellite: () -> Unit,
    onAlignFromCompass: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gyro heading", style = MaterialTheme.typography.titleMedium)
            val alignment = state.alignment
            Text(
                if (alignment == null) {
                    "Aligning north from the compass…"
                } else {
                    val age = Format.duration(Duration.between(alignment.time, state.now))
                    val how = when (alignment.method) {
                        AlignMethod.COMPASS_AUTO -> "from the compass when this screen opened"
                        AlignMethod.COMPASS -> "from the compass"
                        AlignMethod.SUN -> "by pointing at the sun"
                        AlignMethod.SATELLITE -> "by pointing at ${alignment.satelliteName ?: "the satellite"}"
                    }
                    "North was set $how ($age ago). Magnets don't affect it; it may drift slowly, so re-align " +
                        "now and then."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "To align: point the ${if (state.axis == PointingAxis.BACK) "back" else "top"} of the phone " +
                    "at the sun" + (if (state.bright) " or ${state.satelliteName}" else "") + " and tap the button.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.alignMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAlignToSun, enabled = state.canAlignToSun) { Text("Align to sun") }
                if (state.bright) {
                    FilledTonalButton(onClick = onAlignToSatellite, enabled = state.canAlignToSatellite) {
                        Text("Align to ${state.satelliteName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FilledTonalButton(onClick = onAlignFromCompass) { Text("Align from compass") }
            }
            if (!state.canAlignToSun) {
                Text(
                    "The sun is ${if ((state.sun?.elevationDeg ?: -1.0) <= 0) "below the horizon" else "too high"} " +
                        "to align to right now.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InterferenceWarning(fieldMicroTesla: Double?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            "Strong magnetic interference" + (fieldMicroTesla?.let { " (${it.roundToInt()} µT, normal is about 25–65)" } ?: "") +
                ". A magnet (e.g. MagSafe) or metal near the phone makes the compass unreliable. " +
                "Switch \"Heading from\" to Gyro.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun TargetBanner(state: AimUiState) {
    when (val target = state.target) {
        null -> Text("No pass of ${state.satelliteName} in the next 2 days.")
        is AimTarget.Live -> Text(
            "${state.satelliteName} is above the horizon" +
                (target.pass?.let { " — sets in ${Format.countdown(Duration.between(state.now, it.set.time))}" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        is AimTarget.NextRise -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("${state.satelliteName} is below the horizon", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Aiming at where it rises: ${Format.time(target.pass.rise.time)} in the " +
                        "${Format.compassPoint(target.pass.rise.azimuthDeg)}, " +
                        "in ${Format.countdown(Duration.between(state.now, target.pass.rise.time))}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AimRing(state: AimUiState) {
    val aim = state.aim
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dotOutline = MaterialTheme.colorScheme.surface
    val locked = state.onTarget
    val description = when {
        aim == null -> "Waiting for the orientation sensor"
        locked -> "On target"
        else -> "Target ${Format.degrees1(aim.separationDeg)} away"
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = description },
    ) {
        val radius = min(size.width, size.height) / 2f * 0.92f
        val c = center
        val pxPerDeg = radius / RING_RANGE_DEG.toFloat()

        // Rings at 45°, 20° and the 5° "on target" zone.
        drawCircle(outline, radius, c, style = Stroke(width = 2.dp.toPx()))
        drawCircle(outline, 20f * pxPerDeg, c, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
        drawCircle(
            if (locked) primary else outline,
            AimUiState.ON_TARGET_DEG.toFloat() * pxPerDeg,
            c,
            style = Stroke(width = if (locked) 5.dp.toPx() else 2.dp.toPx()),
        )
        // Crosshair: where the phone points.
        val cross = 14.dp.toPx()
        drawLine(onSurface, Offset(c.x - cross, c.y), Offset(c.x + cross, c.y), strokeWidth = 2.dp.toPx())
        drawLine(onSurface, Offset(c.x, c.y - cross), Offset(c.x, c.y + cross), strokeWidth = 2.dp.toPx())

        if (aim != null && state.target != null) {
            val (dx, dy, pinned) = ringPosition(aim, pxPerDeg, radius)
            val dot = Offset(c.x + dx, c.y + dy)
            if (pinned) {
                // Off the ring: a hollow marker on the edge shows which way to turn.
                drawCircle(primary, 14.dp.toPx(), dot, style = Stroke(width = 4.dp.toPx()))
            } else {
                drawCircle(primary, 12.dp.toPx(), dot)
                drawCircle(dotOutline, 12.dp.toPx(), dot, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** Screen offset of the target dot in px, and whether it had to be pinned to the outer ring. */
private fun ringPosition(aim: AimSolution, pxPerDeg: Float, radius: Float): Triple<Float, Float, Boolean> {
    val x = (aim.screenX * pxPerDeg).toFloat()
    val y = (-aim.screenY * pxPerDeg).toFloat() // screen y grows downwards
    val length = hypot(x, y)
    return if (length > radius) Triple(x / length * radius, y / length * radius, true) else Triple(x, y, false)
}

@Composable
private fun Guidance(state: AimUiState) {
    val aim = state.aim
    val text = when {
        aim == null -> "Waiting for the orientation sensor…"
        state.onTarget -> "On target"
        else -> buildList {
            val turn = aim.deltaAzimuthDeg
            val tilt = aim.deltaElevationDeg
            // Azimuth is meaningless when pointing almost straight up or down.
            if (abs(aim.pointing.elevationDeg) < 80 && abs(turn) >= 1.0) {
                add((if (turn > 0) "Turn right " else "Turn left ") + Format.degrees1(abs(turn)))
            }
            if (abs(tilt) >= 1.0) add((if (tilt > 0) "Tilt up " else "Tilt down ") + Format.degrees1(abs(tilt)))
        }.joinToString(" · ").ifEmpty { "Almost there" }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (state.onTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        aim?.let {
            Text("Off by ${Format.degrees1(it.separationDeg)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CalibrationHint() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            "The compass needs calibrating: move the phone in a figure-8 a few times, away from metal and magnets.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Numbers(state: AimUiState) {
    val target = state.target ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            DetailRow(
                if (target is AimTarget.Live) "${state.satelliteName}" else "Rise point",
                "az ${Format.degrees1(target.direction.azimuthDeg)}  el ${Format.degrees1(target.direction.elevationDeg)}",
            )
            state.aim?.let {
                DetailRow(
                    "Phone points",
                    "az ${Format.degrees1(it.pointing.azimuthDeg)}  el ${Format.degrees1(it.pointing.elevationDeg)}",
                )
            }
            if (target is AimTarget.Live) {
                DetailRow("Distance", Format.km(target.look.rangeKm))
            }
            DetailRow("Magnetic declination", Format.degrees1(state.declinationDeg))
            DetailRow("Heading from", if (state.headingSource == HeadingSource.GYRO && !state.gyroUnavailable) "Gyro" else "Compass")
            DetailRow("Compass calibration", state.compassAccuracy.label())
            state.magneticFieldMicroTesla?.let {
                DetailRow("Magnetic field", "${it.roundToInt()} µT" + if (state.magneticInterference) " (disturbed)" else "")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Azimuths are relative to true north (the compass is corrected for magnetic declination).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun CompassAccuracy.label(): String = when (this) {
    CompassAccuracy.HIGH -> "Good"
    CompassAccuracy.MEDIUM -> "OK"
    CompassAccuracy.LOW -> "Poor"
    CompassAccuracy.UNRELIABLE -> "Not calibrated"
    CompassAccuracy.UNKNOWN -> "Unknown"
}

/** A short vibration when the phone comes on target. */
@Composable
private fun LockOnFeedback(onTarget: Boolean) {
    val haptics = LocalHapticFeedback.current
    val previous = remember { mutableStateOf(false) }
    LaunchedEffect(onTarget) {
        if (onTarget && !previous.value) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        previous.value = onTarget
    }
}

/**
 * While this screen is shown: keep the display on and undimmed, and stay in portrait so the
 * layout doesn't flip when the phone is pointed up at the sky. Both are undone when leaving.
 */
@SuppressLint("SourceLockedOrientationActivity") // deliberate, only while aiming, restored on exit
@Composable
private fun KeepScreenOnInPortrait() {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    DisposableEffect(view, activity) {
        view.keepScreenOn = true
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            view.keepScreenOn = false
            if (activity != null && previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
