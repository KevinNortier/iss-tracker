package io.github.kevinnortier.isstracker.ui.passdetails

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.SatelliteDot
import io.github.kevinnortier.isstracker.ui.rememberNotificationPermissionRequester
import java.time.Duration
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailsRoute(
    noradId: Int,
    riseMillis: Long,
    onBack: () -> Unit,
    onOpenAim: () -> Unit,
    viewModel: PassDetailsViewModel = viewModel(
        key = "pass-$noradId-$riseMillis",
        factory = PassDetailsViewModel.factory(noradId, riseMillis),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifications = rememberNotificationPermissionRequester()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Pass details") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pass = state.pass
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Calculating…")
                }
                state.notFound || pass == null -> Text(
                    "This pass can't be found any more. It may be over, or below the minimum elevation you've set.",
                )
                else -> {
                    Header(state, pass)
                    SkyPlotCard(state, pass)
                    Facts(state, pass)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Find in sky aims at the current or next pass, so only offer it for that one.
                        if (state.findInSkyTracksThis) {
                            Button(onClick = onOpenAim) { Text("Find in sky") }
                        }
                        FilledTonalButton(
                            onClick = {
                                if (state.reminderOn) viewModel.toggleReminder()
                                else notifications.runWithPermission { viewModel.toggleReminder() }
                            },
                        ) {
                            Icon(
                                if (state.reminderOn) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.reminderOn) "Reminder on" else "Remind me")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: PassDetailsUiState, pass: Pass) {
    val zone = ZoneId.systemDefault()
    val today = state.now.atZone(zone).toLocalDate()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SatelliteDot(state.noradId)
            Spacer(Modifier.width(8.dp))
            Text(state.satelliteName, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            Format.day(pass.rise.time.atZone(zone).toLocalDate(), today),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Format.time(pass.rise.time)} – ${Format.time(pass.set.time)}",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (pass.visible && state.bright) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Visible to the naked eye",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        val status = when {
            pass.isInProgress(state.now) -> "In progress — sets in ${Format.countdown(Duration.between(state.now, pass.set.time))}"
            pass.rise.time.isAfter(state.now) -> "Rises in ${Format.countdown(Duration.between(state.now, pass.rise.time))}"
            else -> "Over"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SkyPlotCard(state: PassDetailsUiState, pass: Pass) {
    val outline = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pathColor = MaterialTheme.colorScheme.primary
    val visibleColor = MaterialTheme.colorScheme.tertiary
    val markerFill = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = labelColor)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .semantics {
                        contentDescription = "Sky plot: rises in the ${Format.compassPoint(pass.rise.azimuthDeg)}, " +
                            "highest ${Format.degrees0(pass.apex.elevationDeg)} in the " +
                            "${Format.compassPoint(pass.apex.azimuthDeg)}, sets in the ${Format.compassPoint(pass.set.azimuthDeg)}"
                    },
            ) {
                val labelSpace = 22.dp.toPx()
                val radius = size.minDimension / 2f - labelSpace
                val c = center
                fun point(az: Double, el: Double): Offset {
                    val (x, y) = SkyPlot.position(az, el.coerceAtLeast(0.0))
                    return Offset(c.x + (x * radius).toFloat(), c.y + (y * radius).toFloat())
                }

                // Grid: horizon, 30° and 60° elevation rings, N–S and E–W lines.
                drawCircle(outline, radius, c, style = Stroke(1.5.dp.toPx()))
                val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                for (el in listOf(30.0, 60.0)) {
                    drawCircle(outline, radius * ((90 - el) / 90).toFloat(), c, style = Stroke(1.dp.toPx(), pathEffect = dashed))
                }
                drawLine(outline, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.dp.toPx(), pathEffect = dashed)
                drawLine(outline, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.dp.toPx(), pathEffect = dashed)

                // Compass labels just outside the horizon.
                for ((label, az) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                    val layout = measurer.measure(label, labelStyle)
                    val (x, y) = SkyPlot.position(az, 0.0)
                    val px = c.x + (x * (radius + labelSpace / 2)).toFloat() - layout.size.width / 2f
                    val py = c.y + (y * (radius + labelSpace / 2)).toFloat() - layout.size.height / 2f
                    drawText(layout, topLeft = Offset(px, py))
                }
                // Elevation ring labels.
                for (el in listOf(30, 60)) {
                    val layout = measurer.measure("$el°", labelStyle.copy(color = outline))
                    val p = point(45.0, el.toDouble())
                    drawText(layout, topLeft = Offset(p.x + 2.dp.toPx(), p.y))
                }

                // The path: thicker and in the "visible" colour where it can be seen with the naked eye.
                for ((a, b) in state.track.zipWithNext()) {
                    val visible = a.visible && b.visible
                    drawLine(
                        if (visible) visibleColor else pathColor,
                        point(a.azimuthDeg, a.elevationDeg),
                        point(b.azimuthDeg, b.elevationDeg),
                        strokeWidth = if (visible) 6.dp.toPx() else 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // Rise (open circle), highest point (dot) and set (arrow showing the direction of travel).
                val rise = point(pass.rise.azimuthDeg, pass.rise.elevationDeg)
                drawCircle(markerFill, 6.dp.toPx(), rise)
                drawCircle(pathColor, 6.dp.toPx(), rise, style = Stroke(2.5.dp.toPx()))
                drawCircle(pathColor, 5.dp.toPx(), point(pass.apex.azimuthDeg, pass.apex.elevationDeg))
                if (state.track.size >= 2) {
                    val beforeEnd = state.track[state.track.size - 2]
                    drawArrowHead(
                        from = point(beforeEnd.azimuthDeg, beforeEnd.elevationDeg),
                        to = point(pass.set.azimuthDeg, pass.set.elevationDeg),
                        color = pathColor,
                        size = 10.dp.toPx(),
                    )
                }

                // Where it is right now, during the pass.
                state.currentLook?.let { look ->
                    val p = point(look.azimuthDeg, look.elevationDeg)
                    drawCircle(visibleColor, 9.dp.toPx(), p)
                    drawCircle(markerFill, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }
            }
            Spacer(Modifier.height(8.dp))
            Legend(pathColor, visibleColor, labelStyle, if (state.bright) "Visible to the naked eye" else "Sunlit in a dark sky")
        }
    }
}

private fun DrawScope.drawArrowHead(from: Offset, to: Offset, color: Color, size: Float) {
    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val spread = Math.toRadians(150.0)
    for (side in listOf(-1, 1)) {
        val a = angle + side * spread
        val end = Offset(to.x + (size * cos(a)).toFloat(), to.y + (size * sin(a)).toFloat())
        drawLine(color, to, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun Legend(pathColor: Color, visibleColor: Color, style: TextStyle, visibleText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LegendRow(pathColor, 3.dp, "Path (centre = straight up, edge = horizon, north up)", style)
        LegendRow(visibleColor, 6.dp, visibleText, style)
    }
}

@Composable
private fun LegendRow(color: Color, thickness: androidx.compose.ui.unit.Dp, text: String, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), thickness.toPx(), cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = style)
    }
}

@Composable
private fun Facts(state: PassDetailsUiState, pass: Pass) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            DetailRow(
                "Rises",
                "${Format.timeWithSeconds(pass.rise.time)} · ${Format.compassPoint(pass.rise.azimuthDeg)} " +
                    Format.degrees0(pass.rise.azimuthDeg),
            )
            DetailRow(
                "Highest",
                "${Format.timeWithSeconds(pass.apex.time)} · ${Format.degrees1(pass.apex.elevationDeg)} " +
                    "in the ${Format.compassPoint(pass.apex.azimuthDeg)}",
            )
            DetailRow(
                "Sets",
                "${Format.timeWithSeconds(pass.set.time)} · ${Format.compassPoint(pass.set.azimuthDeg)} " +
                    Format.degrees0(pass.set.azimuthDeg),
            )
            DetailRow("Duration", Format.passDuration(pass.duration))
            if (pass.visible && pass.visibleFrom != null && pass.visibleUntil != null) {
                DetailRow(
                    if (state.bright) "Visible to the eye" else "Sunlit (likely too faint to see)",
                    "${Format.time(pass.visibleFrom)}–${Format.time(pass.visibleUntil)}",
                )
            } else if (state.bright) {
                DetailRow("Visible to the eye", "No (daylight or Earth's shadow)")
            } else {
                DetailRow("Sunlit", "No (daylight or Earth's shadow)")
            }
            state.currentLook?.let {
                DetailRow("Now", "az ${Format.degrees1(it.azimuthDeg)}  el ${Format.degrees1(it.elevationDeg)}")
            }
        }
    }
}
