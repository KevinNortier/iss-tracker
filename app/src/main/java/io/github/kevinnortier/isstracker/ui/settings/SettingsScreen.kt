package io.github.kevinnortier.isstracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.data.settings.AppSettings
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.NotifyTrigger
import io.github.kevinnortier.isstracker.notify.ReminderNotifications
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import io.github.kevinnortier.isstracker.ui.rememberNotificationPermissionRequester
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onLocationModeChange = viewModel::setLocationMode,
        onMinElevationChange = viewModel::setMinElevation,
        onNotifyTriggerChange = viewModel::setNotifyTrigger,
        onNotifyLeadChange = viewModel::setNotifyLeadMinutes,
        onPointingAxisChange = viewModel::setPointingAxis,
        onNameChange = { viewModel.updateForm(name = it) },
        onLatitudeChange = { viewModel.updateForm(latitude = it) },
        onLongitudeChange = { viewModel.updateForm(longitude = it) },
        onAltitudeChange = { viewModel.updateForm(altitude = it) },
        onFillFromLastFix = viewModel::fillFromLastFix,
        onSave = viewModel::saveLocation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onLocationModeChange: (LocationMode) -> Unit,
    onMinElevationChange: (Double) -> Unit,
    onNotifyTriggerChange: (NotifyTrigger) -> Unit,
    onNotifyLeadChange: (Int) -> Unit,
    onPointingAxisChange: (PointingAxis) -> Unit,
    onNameChange: (String) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onAltitudeChange: (String) -> Unit,
    onFillFromLastFix: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Text("Location", style = MaterialTheme.typography.titleLarge)
            Column(Modifier.selectableGroup()) {
                ModeOption(
                    title = "Current location",
                    subtitle = "From the phone's GPS or network location",
                    selected = state.settings.locationMode == LocationMode.CURRENT,
                    onClick = { onLocationModeChange(LocationMode.CURRENT) },
                )
                ModeOption(
                    title = "Saved location",
                    subtitle = "A fixed position you enter below",
                    selected = state.settings.locationMode == LocationMode.SAVED,
                    onClick = { onLocationModeChange(LocationMode.SAVED) },
                )
            }

            SavedLocationCard(
                state = state,
                onNameChange = onNameChange,
                onLatitudeChange = onLatitudeChange,
                onLongitudeChange = onLongitudeChange,
                onAltitudeChange = onAltitudeChange,
                onFillFromLastFix = onFillFromLastFix,
                onSave = onSave,
            )

            Text(
                "Your location is only used on this phone to calculate where the ISS is. " +
                    "It is never sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            Text("Passes", style = MaterialTheme.typography.titleLarge)
            Text("Minimum elevation", style = MaterialTheme.typography.bodyLarge)
            Text(
                "A pass only counts while the ISS is at least this high above the horizon. " +
                    "0° shows every pass; higher values hide low passes behind trees and buildings.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppSettings.MIN_ELEVATION_CHOICES.forEach { degrees ->
                    FilterChip(
                        selected = state.settings.minElevationDeg == degrees,
                        onClick = { onMinElevationChange(degrees) },
                        label = { Text("${degrees.toInt()}°") },
                    )
                }
            }

            HorizontalDivider()

            NotificationSettings(
                trigger = state.settings.notifyTrigger,
                leadMinutes = state.settings.notifyLeadMinutes,
                onTriggerChange = onNotifyTriggerChange,
                onLeadChange = onNotifyLeadChange,
            )

            HorizontalDivider()

            Text("Find in sky", style = MaterialTheme.typography.titleLarge)
            Text("Point at the ISS with", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Back: hold the phone like a camera. Top: e.g. with the phone strapped along an antenna, " +
                    "binoculars or a camera.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.settings.pointingAxis == PointingAxis.BACK,
                    onClick = { onPointingAxisChange(PointingAxis.BACK) },
                    label = { Text("Back of phone") },
                )
                FilterChip(
                    selected = state.settings.pointingAxis == PointingAxis.TOP,
                    onClick = { onPointingAxisChange(PointingAxis.TOP) },
                    label = { Text("Top of phone") },
                )
            }
        }
    }
}

@Composable
private fun NotificationSettings(
    trigger: NotifyTrigger,
    leadMinutes: Int,
    onTriggerChange: (NotifyTrigger) -> Unit,
    onLeadChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val notifications = rememberNotificationPermissionRequester()

    Text("Reminders", style = MaterialTheme.typography.titleLarge)
    Text(
        "Tap the bell on a pass to get a reminder before it.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Remind me before", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = trigger == NotifyTrigger.RISE,
            onClick = { onTriggerChange(NotifyTrigger.RISE) },
            label = { Text("It rises") },
        )
        FilterChip(
            selected = trigger == NotifyTrigger.APEX,
            onClick = { onTriggerChange(NotifyTrigger.APEX) },
            label = { Text("It is highest") },
        )
    }
    Text("How long before", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppSettings.NOTIFY_LEAD_CHOICES.forEach { minutes ->
            FilterChip(
                selected = leadMinutes == minutes,
                onClick = { onLeadChange(minutes) },
                label = { Text("$minutes min") },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { notifications.runWithPermission { ReminderNotifications.showTest(context) } }) {
            Text("Send test notification")
        }
        if (notifications.denied) {
            OutlinedButton(onClick = notifications::openSystemSettings) { Text("Notification settings") }
        }
    }
    if (notifications.denied) {
        Text(
            "Notifications are turned off for ISS Tracker.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavedLocationCard(
    state: SettingsUiState,
    onNameChange: (String) -> Unit,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onAltitudeChange: (String) -> Unit,
    onFillFromLastFix: () -> Unit,
    onSave: () -> Unit,
) {
    val form = state.form
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved location", style = MaterialTheme.typography.titleMedium)
            Text(
                "Decimal degrees. Use negative values for south and west.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = onNameChange,
                label = { Text("Name (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField("Latitude", form.latitude, form.errors.latitude, onLatitudeChange, decimal)
            NumberField("Longitude", form.longitude, form.errors.longitude, onLongitudeChange, decimal)
            NumberField(
                "Altitude (m, optional)",
                form.altitude,
                form.errors.altitude,
                onAltitudeChange,
                decimal.copy(imeAction = ImeAction.Done),
            )
            form.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text("Save and use") }
                if (state.settings.lastFix != null) {
                    OutlinedButton(onClick = onFillFromLastFix) { Text("Fill in current position") }
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
    keyboardOptions: KeyboardOptions,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    ISSTrackerTheme {
        SettingsScreen(
            state = SettingsUiState(form = SavedLocationForm(name = "Greenwich", latitude = "51.4769", longitude = "-0.0005")),
            onBack = {},
            onLocationModeChange = {},
            onMinElevationChange = {},
            onNotifyTriggerChange = {},
            onNotifyLeadChange = {},
            onPointingAxisChange = {},
            onNameChange = {},
            onLatitudeChange = {},
            onLongitudeChange = {},
            onAltitudeChange = {},
            onFillFromLastFix = {},
            onSave = {},
        )
    }
}
