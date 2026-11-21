package io.github.kevinnortier.isstracker.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.ui.DetailRow
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.location.LocationAutoRefresh
import io.github.kevinnortier.isstracker.ui.location.LocationCard
import io.github.kevinnortier.isstracker.ui.location.LocationViewModel
import java.time.Duration

/** Detailed live position of the satellite, plus location and orbit data status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveRoute(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LiveViewModel = viewModel(factory = LiveViewModel.Factory),
    locationViewModel: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val location by locationViewModel.uiState.collectAsStateWithLifecycle()
    LocationAutoRefresh(locationViewModel)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Live position") },
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
            PositionCard(state)
            LocationCard(location, state.now, locationViewModel, onOpenSettings)
            OrbitDataCard(state, viewModel::refreshTle)
        }
    }
}

@Composable
private fun PositionCard(state: LiveUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${state.satelliteName} now", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val obs = state.observation
            when {
                state.calculationError != null -> Text(
                    "Could not calculate position: ${state.calculationError}",
                    color = MaterialTheme.colorScheme.error,
                )
                obs != null -> ObservationDetails(obs)
                !state.hasObserver -> Text("Choose a location below to see where the ISS is.")
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
