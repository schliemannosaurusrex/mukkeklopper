package de.schliemannosaurusrex.mukkeklopper.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schliemannosaurusrex.mukkeklopper.sync.SyncFailure
import de.schliemannosaurusrex.mukkeklopper.sync.SyncViewModel
import de.schliemannosaurusrex.mukkeklopper.sync.displayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncFailuresScreen(
    viewModel: SyncViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val failures by viewModel.lastFailures.collectAsState()
    val context = LocalContext.current
    var expandedPath by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sync failures") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (failures.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No failures recorded", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                viewModel.retryFailed(failures.map { it.relPath })
                onBack()
            }) { Text("Retry failed") }
            OutlinedButton(onClick = { copyReport(context, failures) }) { Text("Copy report") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(failures) { failure ->
                FailureRow(
                    failure = failure,
                    expanded = expandedPath == failure.relPath,
                    onToggle = {
                        expandedPath = if (expandedPath == failure.relPath) null else failure.relPath
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FailureRow(failure: SyncFailure, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(failure.relPath, style = MaterialTheme.typography.bodyMedium)
        Text(
            failure.reason.displayText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (expanded) {
            Text(
                failure.detail,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun copyReport(context: android.content.Context, failures: List<SyncFailure>) {
    val report = failures.joinToString("\n") { "${it.relPath}: ${it.reason.displayText()} (${it.detail})" }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Sync failures", report))
}
