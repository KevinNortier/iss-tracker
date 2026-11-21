package io.github.kevinnortier.isstracker.ui.live

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.data.settings.LocationFix
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.Observer
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme
import java.time.Duration
import java.time.Instant

@Composable
fun LiveRoute(
    onOpenSettings: () -> Unit,
    viewModel: LiveViewModel = viewModel(factory = LiveViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // True after the user has answered the permission dialog with "deny" at least once.
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = result.values.none { it }
        viewModel.onScreenVisible()
    }

    LifecycleResumeEffect(Unit) {
        viewModel.onScreenVisible()
        onPauseOrDispose { }
    }

    LiveScreen(
        state = state,
        permissionDenied = permissionDenied,
        onRefreshTle = viewModel::refreshTle,
        onUpdateLocation = viewModel::updateLocation,
        onRequestPermission = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        },
        onOpenAppSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    state: LiveUiState,
    permissionDenied: Boolean,
    onRefreshTle: () -> Unit,
    onUpdateLocation: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("${state.satelliteName} Tracker") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
            PositionCard(state)
            LocationCard(
                state = state,
                permissionDenied = permissionDenied,
                onUpdateLocation = onUpdateLocation,
                onRequestPermission = onRequestPermission,
                onOpenAppSettings = onOpenAppSettings,
                onOpenSettings = onOpenSettings,
            )
            OrbitDataCard(state, onRefreshTle)
        }
    }
}

@Composable
private fun PositionCard(state: LiveUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Position now", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val obs = state.observation
            when {
                state.calculationError != null -> Text(
                    "Could not calculate position: ${state.calculationError}",
                    color = MaterialTheme.colorScheme.error,
                )
                obs != null -> ObservationDetails(obs)
                state.observer == null -> Text("Choose a location below to see where the ISS is.")
                state.tleStatus is TleStatus.Failed -> Text("No orbit data yet.")
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Waiting for orbit data…")
                }
            }
        }
    }
}

@Composable
private fun ObservationDetails(obs: Observation) {
    val look = obs.look
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        BigValue(label = "Azimuth", value = Format.degrees1(look.azimuthDeg), sub = Format.compassPoint(look.azimuthDeg))
        BigValue(
            label = "Elevation",
            value = Format.degrees1(look.elevationDeg),
            sub = if (look.elevationDeg > 0) "Above horizon" else "Below horizon",
        )
    }
    Spacer(Modifier.height(12.dp))
    DetailRow("Distance", Format.km(look.rangeKm))
    DetailRow(
        "Range rate",
        Format.kmPerSec(look.rangeRateKmPerSec) + if (look.rangeRateKmPerSec < 0) " (approaching)" else " (receding)",
    )
    DetailRow("In sunlight", if (obs.sunlit) "Yes" else "No (Earth's shadow)")
    DetailRow("Sun elevation", Format.degrees1(obs.sunElevationDeg))
    DetailRow("Visible to the eye", if (obs.visible) "Yes" else "No")
}

@Composable
private fun LocationCard(
    state: LiveUiState,
    permissionDenied: Boolean,
    onUpdateLocation: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your location", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when (state.locationMode) {
                LocationMode.SAVED -> {
                    val saved = (state.observer as? Observer.Saved)?.saved
                    if (saved == null) {
                        Text("No saved location yet.")
                    } else {
                        DetailRow("Source", "Saved" + if (saved.name.isNotBlank()) ": ${saved.name}" else "")
                        PositionRows(saved.location)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenSettings) { Text("Change location") }
                }

                LocationMode.CURRENT -> CurrentLocationContent(
                    state = state,
                    permissionDenied = permissionDenied,
                    onUpdateLocation = onUpdateLocation,
                    onRequestPermission = onRequestPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun CurrentLocationContent(
    state: LiveUiState,
    permissionDenied: Boolean,
    onUpdateLocation: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val fix = (state.observer as? Observer.Current)?.fix

    if (state.locationPermission == LocationPermission.NONE) {
        Text(
            "Location access is needed to calculate where the ISS is from where you are. " +
                "Your location stays on this phone.",
        )
        if (permissionDenied) {
            Spacer(Modifier.height(8.dp))
            Text(
                "If no dialog appears, allow location for ISS Tracker in the system settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRequestPermission) { Text("Allow location") }
            if (permissionDenied) OutlinedButton(onClick = onOpenAppSettings) { Text("System settings") }
            OutlinedButton(onClick = onOpenSettings) { Text("Enter manually") }
        }
        if (fix != null) {
            Spacer(Modifier.height(12.dp))
            Text("Using the last known position:", style = MaterialTheme.typography.bodySmall)
            FixRows(fix, state.now)
        }
        return
    }

    if (fix != null) {
        DetailRow("Source", "Current location")
        FixRows(fix, state.now)
        if (state.locationPermission == LocationPermission.APPROXIMATE) {
            Text(
                "Approximate location only. Fine for pass times; allow precise location for antenna aiming.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else if (state.locationStatus !is LocationStatus.Locating) {
        Text("No position yet.")
    }

    when (val status = state.locationStatus) {
        LocationStatus.Locating -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text("Getting position…")
            }
        }
        is LocationStatus.Failed -> {
            Spacer(Modifier.height(8.dp))
            Text(status.message, color = MaterialTheme.colorScheme.error)
        }
        LocationStatus.Idle -> Unit
    }

    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onUpdateLocation, enabled = state.locationStatus !is LocationStatus.Locating) {
            Text("Update location")
        }
        OutlinedButton(onClick = onOpenSettings) { Text("Use saved location") }
    }
}

@Composable
private fun FixRows(fix: LocationFix, now: Instant) {
    PositionRows(fix.location)
    fix.accuracyMeters?.let { DetailRow("Accuracy", Format.accuracy(it)) }
    DetailRow("Updated", "${Format.duration(Duration.between(fix.time, now))} ago (${fix.provider})")
}

@Composable
private fun PositionRows(location: GeodeticLocation) {
    DetailRow("Position", Format.coordinates(location.latitudeDeg, location.longitudeDeg))
    DetailRow("Altitude", Format.meters(location.altitudeMeters))
}

@Composable
private fun OrbitDataCard(state: LiveUiState, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Orbit data (TLE)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            when (val status = state.tleStatus) {
                TleStatus.Loading -> Text("Loading…")
                is TleStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                is TleStatus.Loaded -> {
                    val tle = status.cached.tle
                    val epochAge = Duration.between(tle.epoch, state.now)
                    DetailRow("Satellite", "${tle.name ?: "?"} (#${tle.noradId})")
                    DetailRow("Data from", "${Format.dateTime(tle.epoch)} (${Format.duration(epochAge)} ago)")
                    DetailRow(
                        "Downloaded",
                        "${Format.duration(Duration.between(status.cached.fetchedAt, state.now))} ago",
                    )
                    if (epochAge > LiveViewModel.TLE_OLD_AFTER) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Orbit data is more than ${LiveViewModel.TLE_OLD_AFTER.toDays()} days old. " +
                                "Positions may be inaccurate.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            state.refreshMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRefresh, enabled = !state.refreshing) {
                Text(if (state.refreshing) "Refreshing…" else "Refresh")
            }
        }
    }
}

@Composable
private fun BigValue(label: String, value: String, sub: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text(sub, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Preview(showBackground = true)
@Composable
private fun LiveScreenPreview() {
    ISSTrackerTheme {
        LiveScreen(
            state = LiveUiState(
                satelliteName = "ISS",
                locationPermission = LocationPermission.PRECISE,
                observer = Observer.Current(
                    LocationFix(GeodeticLocation(51.4769, -0.0005, 46.0), 12f, Instant.now(), "fused"),
                ),
                observation = Observation(
                    time = Instant.now(),
                    look = LookAngles(213.4, 23.7, 912.0, -3.21),
                    sunlit = true,
                    sunElevationDeg = -12.0,
                ),
                now = Instant.now(),
            ),
            permissionDenied = false,
            onRefreshTle = {},
            onUpdateLocation = {},
            onRequestPermission = {},
            onOpenAppSettings = {},
            onOpenSettings = {},
        )
    }
}
