package io.github.kevinnortier.isstracker.ui.location

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kevinnortier.isstracker.data.settings.LocationFix
import io.github.kevinnortier.isstracker.data.settings.LocationMode
import io.github.kevinnortier.isstracker.data.settings.Observer
import io.github.kevinnortier.isstracker.location.LocationPermission
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import java.time.Duration
import java.time.Instant

/** Refreshes the location whenever the calling screen is resumed. */
@Composable
fun LocationAutoRefresh(viewModel: LocationViewModel) {
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenVisible()
        onPauseOrDispose { }
    }
}

/** The "Your location" card with permission handling. */
@Composable
fun LocationCard(
    state: LocationUiState,
    now: Instant,
    viewModel: LocationViewModel,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    // True after the user has answered the permission dialog with "deny" at least once.
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = result.values.none { it }
        viewModel.onScreenVisible()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Your location", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when (state.mode) {
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
                    now = now,
                    permissionDenied = permissionDenied,
                    onUpdateLocation = viewModel::updateLocation,
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                    onOpenAppSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun CurrentLocationContent(
    state: LocationUiState,
    now: Instant,
    permissionDenied: Boolean,
    onUpdateLocation: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val fix = (state.observer as? Observer.Current)?.fix

    if (state.permission == LocationPermission.NONE) {
        Text(
            "Location access is needed to calculate the passes for where you are. " +
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
            FixRows(fix, now)
        }
        return
    }

    if (fix != null) {
        DetailRow("Source", "Current location")
        FixRows(fix, now)
        if (state.permission == LocationPermission.APPROXIMATE) {
            Text(
                "Approximate location only. Fine for pass times; allow precise location for antenna aiming.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else if (state.status !is LocationStatus.Locating) {
        Text("No position yet.")
    }

    when (val status = state.status) {
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
        Button(onClick = onUpdateLocation, enabled = state.status !is LocationStatus.Locating) {
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

/** One-line description of the location source, for compact footers. */
fun LocationUiState.summary(now: Instant): String = when (val o = observer) {
    null -> "No location"
    is Observer.Saved -> "Saved location" + if (o.saved.name.isNotBlank()) " (${o.saved.name})" else ""
    is Observer.Current -> "Current location, " +
        (o.fix.accuracyMeters?.let { Format.accuracy(it) + ", " } ?: "") +
        "${Format.duration(Duration.between(o.fix.time, now))} old"
}
