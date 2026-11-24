package io.github.kevinnortier.isstracker.ui.passes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.BuildConfig
import io.github.kevinnortier.isstracker.R
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.notify.ReminderNotifications
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.SatelliteDot
import io.github.kevinnortier.isstracker.ui.location.LocationAutoRefresh
import io.github.kevinnortier.isstracker.ui.location.LocationCard
import io.github.kevinnortier.isstracker.ui.location.LocationUiState
import io.github.kevinnortier.isstracker.ui.location.LocationViewModel
import io.github.kevinnortier.isstracker.ui.location.summary
import io.github.kevinnortier.isstracker.ui.rememberNotificationPermissionRequester
import java.time.Duration
import java.time.ZoneId

/** Home screen: the current or next pass, then all upcoming passes grouped by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassesRoute(
    onOpenLive: (noradId: Int) -> Unit,
    onOpenAim: (noradId: Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSatellites: () -> Unit,
    onOpenPass: (noradId: Int, riseMillis: Long) -> Unit,
    viewModel: PassesViewModel = viewModel(factory = PassesViewModel.Factory),
    locationViewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val location by locationViewModel.uiState.collectAsStateWithLifecycle()
    LocationAutoRefresh(locationViewModel)

    val context = LocalContext.current
    val notifications = rememberNotificationPermissionRequester()
    val onToggleReminder: (SatPass) -> Unit = { pass ->
        if (state.hasReminder(pass)) {
            viewModel.toggleReminder(pass)
        } else {
            notifications.runWithPermission { viewModel.toggleReminder(pass) }
        }
    }
    // Reminders are on but can't be shown: tell the user how to fix it.
    val notificationsBlocked = notifications.denied ||
        (state.reminders.isNotEmpty() && !ReminderNotifications.canNotify(context))

    val zone = ZoneId.systemDefault()
    val today = state.now.atZone(zone).toLocalDate()
    val byDay = state.passes.groupBy { it.pass.rise.time.atZone(zone).toLocalDate() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title)
                        if (BuildConfig.DEBUG) {
                            Spacer(Modifier.size(8.dp))
                            Icon(
                                painterResource(R.drawable.ic_debug_bug),
                                contentDescription = "Debug build",
                                // Same red as the launcher icon's debug badge; clear on dark and light themes.
                                tint = Color(0xFFE53935),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Location problems come first: without a position there is nothing to predict.
            if (location.loaded && (!state.hasObserver || location.permissionNeedsAttention())) {
                item(key = "location") {
                    LocationCard(location, state.now, locationViewModel, onOpenSettings)
                    Spacer(Modifier.height(8.dp))
                }
            }

            when {
                state.noSatellites -> item(key = "no-satellites") { NoSatellitesCard(onOpenSatellites) }
                state.loading -> item(key = "loading") { LoadingRow("Calculating passes…") }
                state.error != null -> item(key = "error") {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
                state.hasObserver -> {
                    if (notificationsBlocked) {
                        item(key = "notifications-blocked") {
                            NotificationsBlockedCard(onOpenSettings = notifications::openSystemSettings)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    item(key = "filter") { FilterChips(state, viewModel::toggleFilter, viewModel::toggleVisibleOnly) }
                    item(key = "headline") {
                        HeadlineCard(state, onOpenLive, onOpenAim, onToggleReminder)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.withoutData.isNotEmpty()) {
                        item(key = "without-data") {
                            Text(
                                "No orbit data for ${state.withoutData.joinToString { it.displayName }}, so its passes " +
                                    "aren't shown.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (state.passes.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                if (state.visibleOnly) {
                                    "No passes visible to the naked eye in the next 10 days."
                                } else {
                                    "No passes above ${Format.degrees0(state.minElevationDeg)} in the next 10 days."
                                },
                            )
                        }
                    }
                    byDay.forEach { (date, passes) ->
                        item(key = "day-$date") {
                            Text(
                                Format.day(date, today),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(passes, key = { "pass-${it.noradId}-${it.pass.rise.time.toEpochMilli()}" }) { sp ->
                            PassRow(
                                sp = sp,
                                showName = state.showSatelliteNames,
                                highlighted = sp in state.current,
                                reminderOn = state.hasReminder(sp),
                                onToggleReminder = { onToggleReminder(sp) },
                                onClick = { onOpenPass(sp.noradId, sp.pass.rise.time.toEpochMilli()) },
                            )
                        }
                    }
                    item(key = "footer") {
                        Footer(state, location.summary(state.now))
                    }
                }
            }
        }
    }
}

private fun LocationUiState.permissionNeedsAttention(): Boolean =
    mode == LocationMode.CURRENT && permission == LocationPermission.NONE

@Composable
private fun NoSatellitesCard(onOpenSatellites: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No satellites", style = MaterialTheme.typography.titleMedium)
            Text("Add the satellites you want to follow, like the ISS, Tiangong or amateur radio satellites.")
            Button(onClick = onOpenSatellites) { Text("Add satellites") }
        }
    }
}

/** "Visible" (naked-eye passes only), plus "All" and one chip per tracked satellite when there are several. */
@Composable
private fun FilterChips(state: PassesUiState, onToggle: (Int?) -> Unit, onToggleVisibleOnly: () -> Unit) {
    val all = state.shown.size == state.tracked.size
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.visibleOnly,
            onClick = onToggleVisibleOnly,
            label = { Text("Visible") },
            leadingIcon = { VisibleIcon() },
        )
        if (state.tracked.size > 1) {
            FilterChip(selected = all, onClick = { onToggle(null) }, label = { Text("All") })
            state.tracked.forEach { s ->
                FilterChip(
                    selected = !all && s.noradId in state.shown,
                    onClick = { onToggle(s.noradId) },
                    label = { Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { SatelliteDot(s.noradId, size = 8.dp) },
                )
            }
        }
    }
}

@Composable
private fun NotificationsBlockedCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Notifications are turned off for ISS Tracker, so pass reminders can't be shown.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenSettings) { Text("Notification settings") }
        }
    }
}

@Composable
private fun HeadlineCard(
    state: PassesUiState,
    onOpenLive: (noradId: Int) -> Unit,
    onOpenAim: (noradId: Int) -> Unit,
    onToggleReminder: (SatPass) -> Unit,
) {
    val current = state.current.firstOrNull()
    val next = state.next
    val shownPass = current ?: next
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (current != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            when {
                current != null -> CurrentPass(state, current)
                next != null -> NextPass(state, next)
                else -> Text("No upcoming passes.")
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shownPass != null) {
                    Button(onClick = { onOpenAim(shownPass.noradId) }) { Text("Find in sky") }
                    FilledTonalButton(onClick = { onOpenLive(shownPass.noradId) }) { Text("Details") }
                }
                if (current == null && next != null) {
                    val on = state.hasReminder(next)
                    FilledTonalButton(onClick = { onToggleReminder(next) }) {
                        Icon(
                            if (on) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (on) "Reminder on" else "Remind me")
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentPass(state: PassesUiState, sp: SatPass) {
    val pass = sp.pass
    CardTitle("Passing now", sp, state.showSatelliteNames)
    if (state.current.size > 1) {
        Text(
            "+${state.current.size - 1} more passing now: " + state.current.drop(1).joinToString { it.satellite.displayName },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(8.dp))
    state.currentLook?.let { look ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BigValue("Azimuth", Format.degrees1(look.azimuthDeg), Format.compassPoint(look.azimuthDeg))
            BigValue("Elevation", Format.degrees1(look.elevationDeg), "")
        }
        Spacer(Modifier.height(8.dp))
    }
    DetailRow("Sets in", Format.countdown(Duration.between(state.now, pass.set.time)))
    DetailRow("Highest", "${Format.degrees0(pass.apex.elevationDeg)} at ${Format.time(pass.apex.time)}")
    DetailRow("Sets", "${Format.time(pass.set.time)} in the ${Format.compassPoint(pass.set.azimuthDeg)}")
    VisibilityNote(sp)
}

@Composable
private fun NextPass(state: PassesUiState, sp: SatPass) {
    val pass = sp.pass
    CardTitle("Next pass", sp, state.showSatelliteNames)
    Spacer(Modifier.height(4.dp))
    Text(
        "in ${Format.countdown(Duration.between(state.now, pass.rise.time))}",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    DetailRow(
        "Rises",
        "${Format.time(pass.rise.time)} in the ${Format.compassPoint(pass.rise.azimuthDeg)} " +
            "(${Format.degrees0(pass.rise.azimuthDeg)})",
    )
    DetailRow(
        "Highest",
        "${Format.degrees0(pass.apex.elevationDeg)} at ${Format.time(pass.apex.time)}, " +
            Format.compassPoint(pass.apex.azimuthDeg),
    )
    DetailRow(
        "Sets",
        "${Format.time(pass.set.time)} in the ${Format.compassPoint(pass.set.azimuthDeg)} " +
            "(${Format.degrees0(pass.set.azimuthDeg)})",
    )
    DetailRow("Duration", Format.passDuration(pass.duration))
    VisibilityNote(sp)
}

@Composable
private fun VisibilityNote(sp: SatPass) {
    val pass = sp.pass
    if (pass.visibleFrom == null || pass.visibleUntil == null) return
    val window = "${Format.time(pass.visibleFrom)}–${Format.time(pass.visibleUntil)}"
    when {
        sp.visibleToEye -> DetailRow("Visible to the eye", window)
        sp.sunlitOnly -> DetailRow("Sunlit (likely too faint to see)", window)
    }
}

/** "Next pass ★" plus, with several satellites, the satellite's dot and name. */
@Composable
private fun CardTitle(title: String, sp: SatPass, showName: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (sp.visibleToEye) {
            Spacer(Modifier.size(8.dp))
            VisibleIcon()
        }
    }
    if (showName) SatelliteLabel(sp.satellite, MaterialTheme.typography.titleSmall.fontSize.value)
}

@Composable
private fun SatelliteLabel(satellite: Satellite, fontSizeSp: Float = 12f) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        SatelliteDot(satellite.noradId, size = 8.dp)
        Spacer(Modifier.size(6.dp))
        Text(
            satellite.displayName,
            style = if (fontSizeSp >= 14f) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VisibleIcon() {
    Icon(
        Icons.Filled.Star,
        contentDescription = "Visible to the naked eye",
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun PassRow(
    sp: SatPass,
    showName: Boolean,
    highlighted: Boolean,
    reminderOn: Boolean,
    onToggleReminder: () -> Unit,
    onClick: () -> Unit,
) {
    val pass = sp.pass
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = (if (showName) "${sp.satellite.displayName}: " else "") +
                    "Pass from ${Format.time(pass.rise.time)} to ${Format.time(pass.set.time)}, " +
                    "highest ${Format.degrees0(pass.apex.elevationDeg)}" +
                    (if (sp.visibleToEye) ", visible" else "") +
                    if (reminderOn) ", reminder on" else ""
            },
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (showName) SatelliteLabel(sp.satellite)
            // The bell button (48 dp) is shifted up so its centre lines up with the elevation number,
            // like the star; "max" stays under the number.
            val numberLineHeight = MaterialTheme.typography.titleLarge.lineHeight
            val bellOffset = with(LocalDensity.current) { numberLineHeight.toDp() / 2 } - 24.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    PassTimes(pass)
                    PassDirections(sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    // The star sits on the same line as the elevation, centred on the number.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sp.visibleToEye) {
                            VisibleIcon()
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            Format.degrees0(pass.apex.elevationDeg),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text("max", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onToggleReminder, modifier = Modifier.offset(y = bellOffset)) {
                    Icon(
                        if (reminderOn) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                        contentDescription = if (reminderOn) "Turn off reminder" else "Remind me about this pass",
                        tint = if (reminderOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "rise ↗ highest ↘ set": the arrows tilt up towards the highest point and down towards the set. */
@Composable
private fun PassTimes(pass: Pass) {
    // The arrow icon is mirrored in right-to-left layouts, so the tilt direction flips with it.
    val tilt = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -45f else 45f
    Row(verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.titleMedium
        Text(Format.time(pass.rise.time), style = style)
        TiltedArrow(rotation = -tilt)
        Text(Format.time(pass.apex.time), style = style)
        TiltedArrow(rotation = tilt)
        Text(Format.time(pass.set.time), style = style)
    }
}

/** "WSW → ESE · 10 min 21 s" (plus "· sunlit" for faint satellites in a dark sky). */
@Composable
private fun PassDirections(sp: SatPass) {
    val pass = sp.pass
    Row(verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.bodySmall
        Text(Format.compassPoint(pass.rise.azimuthDeg), style = style)
        TiltedArrow(rotation = 0f, size = 12.dp, gap = 4.dp)
        Text(
            "${Format.compassPoint(pass.set.azimuthDeg)} · ${Format.passDuration(pass.duration)}" +
                if (sp.sunlitOnly) " · sunlit" else "",
            style = style,
        )
    }
}

@Composable
private fun TiltedArrow(rotation: Float, size: Dp = 16.dp, gap: Dp = 6.dp) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null, // the row's description already says "from … to …"
        modifier = Modifier
            .padding(horizontal = gap)
            .size(size)
            .rotate(rotation),
    )
}

/**
 * The "→" glyph is drawn around the middle of the lowercase letters, which looks low next to
 * digits and capitals. Raising it a little centres it on them.
 */
private fun withCenteredArrows(text: String): AnnotatedString = buildAnnotatedString {
    text.split('→').forEachIndexed { i, part ->
        if (i > 0) withStyle(SpanStyle(baselineShift = BaselineShift(ARROW_RAISE))) { append('→') }
        append(part)
    }
}

/** How far to raise arrows, as a fraction of the font size. */
private const val ARROW_RAISE = 0.12f

@Composable
private fun Footer(state: PassesUiState, locationSummary: String) {
    Column(Modifier.padding(top = 16.dp)) {
        val tleAge = state.oldestTleEpoch?.let {
            (if (state.shown.size > 1) "up to " else "") + Format.duration(Duration.between(it, state.now)) + " old"
        } ?: "–"
        Text(
            withCenteredArrows(
                (if (state.visibleOnly) "Passes visible to the naked eye" else "Passes") +
                    " above ${Format.degrees0(state.minElevationDeg)}, 10 days ahead. " +
                    "Times are rise → highest → set.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Orbit data: $tleAge. Location: $locationSummary.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            VisibleIcon()
            Spacer(Modifier.size(4.dp))
            Text("= visible to the naked eye", style = MaterialTheme.typography.bodySmall)
        }
        if (state.passes.any { it.sunlitOnly }) {
            Text(
                "\"sunlit\" = in sunlight while your sky is dark, but most satellites are too faint to see.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BigValue(label: String, value: String, sub: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(text)
    }
}
