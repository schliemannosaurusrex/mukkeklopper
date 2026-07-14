package de.schliemannosaurusrex.kaniamp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.schliemannosaurusrex.kaniamp.library.FolderEntry
import de.schliemannosaurusrex.kaniamp.library.LibraryState
import de.schliemannosaurusrex.kaniamp.library.LibraryViewModel
import de.schliemannosaurusrex.kaniamp.library.FolderTree
import de.schliemannosaurusrex.kaniamp.library.Track
import de.schliemannosaurusrex.kaniamp.player.PlayerViewModel
import de.schliemannosaurusrex.kaniamp.settings.LibraryViewMode
import de.schliemannosaurusrex.kaniamp.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val writeRequest by viewModel.writeRequest.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showMoveDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionGranted()
    }

    // Fremde Dateien verschieben: Android verlangt eine Bestätigung durch den User.
    val writeRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWriteRequestResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onPermissionGranted()
        else permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    LaunchedEffect(writeRequest) {
        writeRequest?.let {
            writeRequestLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.isSelecting) {
            SelectionAppBar(
                count = state.selectedTrackIds.size,
                onClose = { viewModel.clearSelection() },
                onMove = { showMoveDialog = true },
            )
        } else {
            LibraryAppBar(state = state, viewModel = viewModel)
        }

        if (state.viewMode == LibraryViewMode.FOLDERS && state.hasPermission) {
            Breadcrumb(
                path = state.currentPath,
                onNavigate = { viewModel.openFolder(it) },
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                !state.hasPermission -> CenteredMessage {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Storage permission required to load music")
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                        }) { Text("Grant Permission") }
                    }
                }

                state.isLoading -> CenteredMessage { CircularProgressIndicator() }

                state.tracks.isEmpty() -> CenteredMessage {
                    Text(
                        "No music found.\nSync from your server to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> when (state.viewMode) {
                    LibraryViewMode.FOLDERS -> FolderList(
                        state = state,
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                    )

                    LibraryViewMode.TRACKS -> TrackList(
                        tracks = state.tracks,
                        state = state,
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                    )

                    LibraryViewMode.ALBUMS, LibraryViewMode.ARTISTS -> GroupList(
                        state = state,
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        onNavigateToNowPlaying = onNavigateToNowPlaying,
                    )
                }
            }
        }

        SnackbarHost(snackbarHostState)
    }

    if (showMoveDialog) {
        MoveTargetDialog(
            targets = viewModel.moveTargets(),
            currentPath = state.currentPath,
            onConfirm = { target ->
                viewModel.moveSelectedTo(target)
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
}

// --- App-Bars ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryAppBar(state: LibraryState, viewModel: LibraryViewModel) {
    var showViewMenu by remember { mutableStateOf(false) }
    val inFolders = state.viewMode == LibraryViewMode.FOLDERS

    TopAppBar(
        title = {
            Text(
                when {
                    state.selectedGroup != null -> state.selectedGroup!!
                    inFolders && state.currentPath.isNotEmpty() ->
                        state.currentPath.trimEnd('/').substringAfterLast('/')
                    else -> "Library"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            val canGoBack = state.selectedGroup != null || (inFolders && !state.isAtRoot)
            if (canGoBack) {
                IconButton(onClick = {
                    if (state.selectedGroup != null) viewModel.closeGroup()
                    else viewModel.navigateUp()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
        },
        actions = {
            if (inFolders) {
                // Startverzeichnis markieren: der Stern zeigt, ob die Library hier startet
                IconButton(
                    onClick = { viewModel.toggleStartFolder() },
                    enabled = !state.isAtRoot || state.libraryRoot.isNotEmpty(),
                ) {
                    Icon(
                        if (state.currentIsStartFolder) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (state.currentIsStartFolder) {
                            "Clear start folder"
                        } else {
                            "Set as start folder"
                        },
                        tint = if (state.currentIsStartFolder) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (state.libraryRoot.isNotEmpty() && state.currentPath != state.libraryRoot) {
                    IconButton(onClick = { viewModel.goToStartFolder() }) {
                        Icon(Icons.Default.Folder, contentDescription = "Go to start folder")
                    }
                }
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
            Box {
                IconButton(onClick = { showViewMenu = true }) {
                    Icon(Icons.Default.ViewList, contentDescription = "View mode")
                }
                DropdownMenu(
                    expanded = showViewMenu,
                    onDismissRequest = { showViewMenu = false },
                ) {
                    LibraryViewMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            leadingIcon = {
                                if (mode == state.viewMode) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                viewModel.setViewMode(mode)
                                showViewMenu = false
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(count: Int, onClose: () -> Unit, onMove: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onMove) {
                Icon(Icons.Default.DriveFileMove, contentDescription = "Move to…")
            }
        },
    )
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            "Storage",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onNavigate("") },
        )
        FolderTree.breadcrumbs(path).forEach { (name, target) ->
            Text(" / ", style = MaterialTheme.typography.labelLarge)
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (target == path) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.clickable { onNavigate(target) },
            )
        }
    }
}

// --- Listen --------------------------------------------------------------

@Composable
private fun FolderList(
    state: LibraryState,
    viewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
) {
    val folder = state.folder
    if (folder.subFolders.isEmpty() && folder.tracks.isEmpty()) {
        CenteredMessage {
            Text(
                "This folder is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn {
        items(folder.subFolders, key = { it.path }) { entry ->
            FolderRow(
                entry = entry,
                isStartFolder = entry.path == state.libraryRoot,
                onClick = { viewModel.openFolder(entry.path) },
            )
            HorizontalDivider()
        }
        itemsIndexed(folder.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                selected = track.id in state.selectedTrackIds,
                selectionMode = state.isSelecting,
                onClick = {
                    if (state.isSelecting) {
                        viewModel.toggleSelection(track.id)
                    } else {
                        playerViewModel.playTracks(folder.tracks, index)
                        onNavigateToNowPlaying()
                    }
                },
                onLongClick = { viewModel.toggleSelection(track.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    state: LibraryState,
    viewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
) {
    LazyColumn {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                selected = track.id in state.selectedTrackIds,
                selectionMode = state.isSelecting,
                onClick = {
                    if (state.isSelecting) {
                        viewModel.toggleSelection(track.id)
                    } else {
                        playerViewModel.playTracks(tracks, index)
                        onNavigateToNowPlaying()
                    }
                },
                onLongClick = { viewModel.toggleSelection(track.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun GroupList(
    state: LibraryState,
    viewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
) {
    val byArtist = state.viewMode == LibraryViewMode.ARTISTS
    val groupOf: (Track) -> String = if (byArtist) { t -> t.artist } else { t -> t.album }

    val group = state.selectedGroup
    if (group != null) {
        TrackList(
            tracks = state.tracks.filter { groupOf(it) == group },
            state = state,
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            onNavigateToNowPlaying = onNavigateToNowPlaying,
        )
        return
    }

    val groups = state.tracks
        .groupBy(groupOf)
        .toList()
        .sortedBy { it.first.lowercase() }

    LazyColumn {
        items(groups, key = { it.first }) { (name, tracks) ->
            ListItem(
                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${tracks.size} track(s)") },
                leadingContent = {
                    Icon(
                        if (byArtist) Icons.Default.Person else Icons.Default.Album,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { viewModel.openGroup(name) },
            )
            HorizontalDivider()
        }
    }
}

// --- Zeilen --------------------------------------------------------------

@Composable
private fun FolderRow(entry: FolderEntry, isStartFolder: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("${entry.trackCount} track(s)") },
        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
        trailingContent = {
            if (isStartFolder) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Start folder",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: Track,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${track.artist} · ${track.album}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                if (selectionMode && selected) Icons.Default.Check else Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            Text(
                formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

// --- Dialoge -------------------------------------------------------------

@Composable
private fun MoveTargetDialog(
    targets: List<String>,
    currentPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to…") },
        text = {
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(targets, key = { it }) { target ->
                    ListItem(
                        headlineContent = {
                            Text(
                                target.trimEnd('/'),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        colors = if (target == selected) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            )
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier.clickable { selected = target },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && selected != currentPath,
                onClick = { onConfirm(selected) },
            ) { Text("Move") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
