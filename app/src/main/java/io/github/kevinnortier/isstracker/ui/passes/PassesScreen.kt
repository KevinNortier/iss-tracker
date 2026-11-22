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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.notify.ReminderNotifications
import io.github.kevinnortier.isstracker.orbit.Pass
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.rememberNotificationPermissionRequester
import io.github.kevinnortier.isstracker.ui.location.LocationAutoRefresh
import io.github.kevinnortier.isstracker.ui.location.LocationCard
import io.github.kevinnortier.isstracker.ui.location.LocationUiState
import io.github.kevinnortier.isstracker.ui.location.LocationViewModel
import io.github.kevinnortier.isstracker.ui.location.summary
import java.time.Duration
import java.time.ZoneId

/** Home screen: the current or next pass, then all upcoming passes grouped by day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassesRoute(
    onOpenLive: () -> Unit,
    onOpenAim: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPass: (riseMillis: Long) -> Unit,
    viewModel: PassesViewModel = viewModel(factory = PassesViewModel.Factory),
    locationViewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val location by locationViewModel.uiState.collectAsStateWithLifecycle()
    LocationAutoRefresh(locationViewModel)

    val context = LocalContext.current
    val notifications = rememberNotificationPermissionRequester()
    val onToggleReminder: (Pass) -> Unit = { pass ->
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
    val byDay = state.passes.groupBy { it.rise.time.atZone(zone).toLocalDate() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("${state.satelliteName} passes") },
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
                    item(key = "headline") {
                        HeadlineCard(state, onOpenLive, onOpenAim, onToggleReminder)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.passes.isEmpty()) {
                        item(key = "empty") {
                            Text("No passes above ${Format.degrees0(state.minElevationDeg)} in the next 10 days.")
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
                        items(passes, key = { "pass-${it.rise.time.toEpochMilli()}" }) { pass ->
                            PassRow(
                                pass = pass,
                                highlighted = pass == state.current,
                                reminderOn = state.hasReminder(pass),
                                onToggleReminder = { onToggleReminder(pass) },
                                onClick = { onOpenPass(pass.rise.time.toEpochMilli()) },
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
    onOpenLive: () -> Unit,
    onOpenAim: () -> Unit,
    onToggleReminder: (Pass) -> Unit,
) {
    val current = state.current
    val next = state.next
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
                Button(onClick = onOpenAim) { Text("Find in sky") }
                FilledTonalButton(onClick = onOpenLive) { Text("Details") }
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
private fun CurrentPass(state: PassesUiState, pass: Pass) {
    TitleWithVisibility("Passing now", pass.visible)
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
    VisibilityNote(pass)
}

@Composable
private fun NextPass(state: PassesUiState, pass: Pass) {
    TitleWithVisibility("Next pass", pass.visible)
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
    VisibilityNote(pass)
}

@Composable
private fun VisibilityNote(pass: Pass) {
    if (pass.visible && pass.visibleFrom != null && pass.visibleUntil != null) {
        DetailRow("Visible to the eye", "${Format.time(pass.visibleFrom)}–${Format.time(pass.visibleUntil)}")
    }
}

@Composable
private fun TitleWithVisibility(title: String, visible: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (visible) {
            Spacer(Modifier.size(8.dp))
            VisibleIcon()
        }
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
    pass: Pass,
    highlighted: Boolean,
    reminderOn: Boolean,
    onToggleReminder: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Pass from ${Format.time(pass.rise.time)} to ${Format.time(pass.set.time)}, " +
                    "highest ${Format.degrees0(pass.apex.elevationDeg)}" + (if (pass.visible) ", visible" else "") +
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Format.time(pass.rise.time)} → ${Format.time(pass.apex.time)} → ${Format.time(pass.set.time)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${Format.compassPoint(pass.rise.azimuthDeg)} → ${Format.compassPoint(pass.set.azimuthDeg)} · " +
                        Format.passDuration(pass.duration),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (pass.visible) {
                VisibleIcon()
                Spacer(Modifier.size(12.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.degrees0(pass.apex.elevationDeg),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("max", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onToggleReminder) {
                Icon(
                    if (reminderOn) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                    contentDescription = if (reminderOn) "Turn off reminder" else "Remind me about this pass",
                    tint = if (reminderOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Footer(state: PassesUiState, locationSummary: String) {
    Column(Modifier.padding(top = 16.dp)) {
        val tleAge = state.tleEpoch?.let { Format.duration(Duration.between(it, state.now)) + " old" } ?: "–"
        Text(
            "Passes above ${Format.degrees0(state.minElevationDeg)}, 10 days ahead. " +
                "Times are rise → highest → set.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Orbit data: $tleAge. Location: $locationSummary.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            VisibleIcon()
            Spacer(Modifier.size(4.dp))
            Text("= visible to the naked eye", style = MaterialTheme.typography.bodySmall)
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
