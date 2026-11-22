package io.github.kevinnortier.isstracker.ui.satellites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kevinnortier.isstracker.data.CatalogGroup
import io.github.kevinnortier.isstracker.ui.SatelliteDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSatelliteRoute(
    onBack: () -> Unit,
    viewModel: AddSatelliteViewModel = viewModel(factory = AddSatelliteViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Add satellite") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(state.tab == AddTab.GROUPS, onClick = { viewModel.selectTab(AddTab.GROUPS) }, text = { Text("Groups") })
                Tab(state.tab == AddTab.SEARCH, onClick = { viewModel.selectTab(AddTab.SEARCH) }, text = { Text("Search") })
                Tab(state.tab == AddTab.NORAD, onClick = { viewModel.selectTab(AddTab.NORAD) }, text = { Text("NORAD ID") })
            }
            when (state.tab) {
                AddTab.GROUPS -> GroupsTab(state, viewModel)
                AddTab.SEARCH -> SearchTab(state, viewModel)
                AddTab.NORAD -> NoradTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun GroupsTab(state: AddSatelliteUiState, viewModel: AddSatelliteViewModel) {
    ResultsList(state, state.groupResult, viewModel::add) {
        Text("Satellite groups from CelesTrak:", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogGroup.entries.forEach { group ->
                FilterChip(
                    selected = state.group == group,
                    onClick = { viewModel.selectGroup(group) },
                    label = { Text(group.title) },
                )
            }
        }
        if (state.groupResult == ResultList.Idle) {
            Text("Pick a group to see its satellites.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchTab(state: AddSatelliteUiState, viewModel: AddSatelliteViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canSearch = state.searchText.trim().length >= AddSatelliteViewModel.MIN_SEARCH_LENGTH
    ResultsList(state, state.searchResult, viewModel::add) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchText,
                onValueChange = viewModel::setSearchText,
                label = { Text("Satellite name") },
                placeholder = { Text("e.g. tiangong, so-50, meteor") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    viewModel.search()
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(
                onClick = {
                    keyboard?.hide()
                    viewModel.search()
                },
                enabled = canSearch,
            ) { Text("Search") }
        }
        Text("At least ${AddSatelliteViewModel.MIN_SEARCH_LENGTH} characters.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NoradTab(state: AddSatelliteUiState, viewModel: AddSatelliteViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    ResultsList(state, state.noradResult, viewModel::add, emptyText = "No satellite with that number (or no current data for it).") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.noradText,
                onValueChange = viewModel::setNoradText,
                label = { Text("NORAD catalog number") },
                placeholder = { Text("e.g. 25544") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    viewModel.lookup()
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(
                onClick = {
                    keyboard?.hide()
                    viewModel.lookup()
                },
                enabled = state.noradText.isNotEmpty(),
            ) { Text("Look up") }
        }
    }
}

/** The controls ([header]) followed by the result rows. */
@Composable
private fun ResultsList(
    state: AddSatelliteUiState,
    result: ResultList,
    onAdd: (CatalogItem) -> Unit,
    emptyText: String = "No satellites found.",
    header: @Composable () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                header()
                if (state.full) {
                    Text(
                        "Your list is full. Remove a satellite to add another.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        when (result) {
            ResultList.Idle -> Unit
            ResultList.Loading -> item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text("Asking CelesTrak…")
                }
            }
            is ResultList.Failed -> item(key = "failed") { Text(result.message, color = MaterialTheme.colorScheme.error) }
            is ResultList.Loaded -> {
                if (result.items.isEmpty()) item(key = "empty") { Text(emptyText) }
                items(result.items, key = { it.noradId }) { item ->
                    CatalogRow(item, tracked = item.noradId in state.trackedIds, adding = item.noradId in state.adding, full = state.full, onAdd = { onAdd(item) })
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(item: CatalogItem, tracked: Boolean, adding: Boolean, full: Boolean, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SatelliteDot(item.noradId, size = 10.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (item.lowOrbit) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Text(
                    if (item.lowOrbit) "NORAD ${item.noradId}" else "NORAD ${item.noradId} · high orbit, not supported yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.lowOrbit) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                )
            }
            when {
                tracked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                item.lowOrbit -> FilledTonalButton(onClick = onAdd, enabled = !adding && !full) { Text("Add") }
            }
        }
    }
}
