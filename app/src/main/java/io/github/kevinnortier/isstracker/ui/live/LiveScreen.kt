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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.orbit.LookAngles
import io.github.kevinnortier.isstracker.orbit.Observation
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.theme.ISSTrackerTheme
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Composable
fun LiveRoute(viewModel: LiveViewModel = viewModel(factory = LiveViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LiveScreen(state = state, onRefresh = viewModel::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(state: LiveUiState, onRefresh: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("${state.satelliteName} Tracker") }) },
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
            OrbitDataCard(state, onRefresh)
            LocationCard(state)
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
                obs == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Waiting for orbit data…")
                }
                else -> ObservationDetails(obs)
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
private fun LocationCard(state: LiveUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Observer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            DetailRow("Source", state.locationDescription)
            DetailRow(
                "Position",
                String.format(Locale.ROOT, "%.4f°, %.4f°", state.latitudeDeg, state.longitudeDeg),
            )
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
private fun DetailRow(label: String, value: String) {
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
                locationDescription = "Greenwich (default)",
                latitudeDeg = 51.4769,
                longitudeDeg = -0.0005,
                observation = Observation(
                    time = Instant.now(),
                    look = LookAngles(213.4, 23.7, 912.0, -3.21),
                    sunlit = true,
                    sunElevationDeg = -12.0,
                ),
                now = Instant.now(),
            ),
            onRefresh = {},
        )
    }
}
