package de.schliemannosaurusrex.mukkeklopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schliemannosaurusrex.mukkeklopper.network.SyncDecision
import de.schliemannosaurusrex.mukkeklopper.sync.SyncState
import de.schliemannosaurusrex.mukkeklopper.sync.SyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    viewModel: SyncViewModel = viewModel(),
    modifier: Modifier = Modifier,
    onNavigateToFailures: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val startSync = rememberWithLocalNetworkPermission { viewModel.startSync() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        TopAppBar(title = { Text("Sync") })

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Server", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (settings.serverHost.isBlank()) "Not configured — see Settings"
                    else "${settings.serverUser.ifBlank { "?" }}@${settings.serverHost}:${settings.serverPort}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Remote folder: ${settings.remoteBasePath.ifBlank { "not set" }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Local target: Music/MukkeKlopper/",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            SyncState.Idle -> {
                Button(
                    onClick = startSync,
                    enabled = settings.serverHost.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Sync now")
                }
            }

            SyncState.CheckingNetwork -> RunningCard("Checking network…", viewModel)
            SyncState.Connecting -> RunningCard("Connecting to server…", viewModel)
            SyncState.Indexing -> RunningCard("Comparing server and device…", viewModel)

            is SyncState.Downloading -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Downloading ${s.fileIndex} of ${s.fileCount}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(s.fileName, style = MaterialTheme.typography.bodyMedium)
                        if (s.bytesTotal > 0) {
                            LinearProgressIndicator(
                                progress = { s.bytesDone.toFloat() / s.bytesTotal },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedButton(onClick = { viewModel.cancelSync() }) { Text("Cancel") }
                    }
                }
            }

            is SyncState.Blocked -> BlockedCard(s.decision, viewModel)

            is SyncState.ConfirmDeletions -> {
                // Karte als Fallback hinter dem Dialog, falls dieser weggewischt wird
                RunningCard("Waiting for delete confirmation…", viewModel)
                DeletionDialog(paths = s.paths, viewModel = viewModel)
            }

            is SyncState.Finished -> {
                ResultCard(
                    icon = { Icon(Icons.Default.CloudDone, contentDescription = null) },
                    title = "Sync finished",
                    lines = buildList {
                        add("${s.downloaded} downloaded, ${s.skipped} up to date")
                        if (s.deleted > 0) add("${s.deleted} deleted locally")
                        if (s.deletionsPending > 0) {
                            add("${s.deletionsPending} deletions pending (confirm on next manual sync)")
                        }
                    },
                    onDismiss = { viewModel.dismissResult() },
                    trailing = if (s.failures.isNotEmpty()) {
                        {
                            TextButton(onClick = onNavigateToFailures) {
                                Text("${s.failures.size} failed — view details")
                            }
                        }
                    } else null,
                )
            }

            is SyncState.Failed -> {
                ResultCard(
                    icon = {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    title = "Sync failed",
                    lines = listOf(s.message),
                    onDismiss = { viewModel.dismissResult() },
                )
            }
        }
    }
}

@Composable
private fun RunningCard(text: String, viewModel: SyncViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = { viewModel.cancelSync() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun BlockedCard(decision: SyncDecision, viewModel: SyncViewModel) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Text(
                    when (decision) {
                        SyncDecision.Blocked -> "Sync blocked"
                        SyncDecision.NotConfigured -> "Not configured"
                        is SyncDecision.MitmWarning -> "HOST KEY MISMATCH"
                        is SyncDecision.Unreachable -> "Server unreachable"
                        SyncDecision.Allowed -> "" // kommt hier nicht vor
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                when (decision) {
                    SyncDecision.Blocked ->
                        "You are not on your home network and no VPN is active. " +
                            "Connect WireGuard to sync remotely."
                    SyncDecision.NotConfigured ->
                        "Set the server host in Settings first."
                    is SyncDecision.MitmWarning ->
                        "The server presented a different SSH key than the pinned one. " +
                            "This could be a man-in-the-middle attack. Sync was aborted and " +
                            "the pinned key was NOT changed."
                    is SyncDecision.Unreachable ->
                        "Could not reach the server: ${decision.reason}"
                    SyncDecision.Allowed -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (decision == SyncDecision.Blocked) {
                    Button(onClick = { viewModel.openWireGuard() }) { Text("Open WireGuard") }
                }
                TextButton(onClick = { viewModel.dismissResult() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ResultCard(
    icon: @Composable () -> Unit,
    title: String,
    lines: List<String>,
    onDismiss: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.size(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            trailing?.invoke()
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun DeletionDialog(paths: List<String>, viewModel: SyncViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.confirmDeletions(false) },
        title = { Text("Delete ${paths.size} file(s)?") },
        text = {
            Column {
                Text(
                    "These files were removed on the server. Delete them from this device too?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(paths) { path ->
                        Text(path, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmDeletions(true) }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.confirmDeletions(false) }) { Text("Keep files") }
        },
    )
}
