package io.github.kevinnortier.isstracker.ui.satellites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.ui.Format
import io.github.kevinnortier.isstracker.ui.SatelliteDot
import java.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatellitesRoute(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    viewModel: SatellitesViewModel = viewModel(factory = SatellitesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmRemove by rememberSaveable { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Satellites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.full) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add satellite") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            // Extra bottom space so the last row isn't hidden behind the button.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    if (state.rows.isEmpty() && !state.loading) {
                        "No satellites yet. Add one to see its passes."
                    } else {
                        "Passes of these satellites are shown in the list. " +
                            "${state.rows.size} of ${Satellite.MAX_TRACKED}."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            itemsIndexed(state.rows, key = { _, row -> row.satellite.noradId }) { index, row ->
                SatelliteRow(
                    row = row,
                    state = state,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.rows.lastIndex,
                    onMoveUp = { viewModel.move(index, -1) },
                    onMoveDown = { viewModel.move(index, +1) },
                    onRemove = { confirmRemove = row.satellite.noradId },
                )
            }
            if (state.full) {
                item(key = "full") {
                    Text(
                        "The list is full (${Satellite.MAX_TRACKED}). Remove a satellite to add another.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    val removing = state.rows.firstOrNull { it.satellite.noradId == confirmRemove }?.satellite
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${removing.displayName}?") },
            text = { Text("Its passes disappear from the list and its reminders are turned off.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(removing.noradId)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SatelliteRow(
    row: TrackedRow,
    state: SatellitesUiState,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = row.satellite
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SatelliteDot(s.colorIndex, size = 12.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                    if (s.bright) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Bright: visible to the eye when sunlit",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                val data = row.tleEpoch?.let { "orbit data ${Format.duration(Duration.between(it, state.now))} old" }
                    ?: "no orbit data yet"
                Text("NORAD ${s.noradId} · $data", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${s.displayName} up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${s.displayName} down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${s.displayName}")
            }
        }
    }
}
