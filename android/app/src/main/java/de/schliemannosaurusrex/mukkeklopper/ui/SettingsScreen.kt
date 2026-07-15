package de.schliemannosaurusrex.mukkeklopper.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schliemannosaurusrex.mukkeklopper.network.SyncDecision
import de.schliemannosaurusrex.mukkeklopper.settings.AuthMethod
import de.schliemannosaurusrex.mukkeklopper.settings.ConfigExport
import de.schliemannosaurusrex.mukkeklopper.settings.ConnectionTest
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onNavigateToDebugLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val connectionTest by viewModel.connectionTest.collectAsState()
    val sshPublicKey by viewModel.sshPublicKey.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showHostDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showClearKeyDialog by remember { mutableStateOf(false) }
    var showModeBDisclosure by remember { mutableStateOf(false) }
    var showAddSsidDialog by remember { mutableStateOf(false) }
    var showUserDialog by remember { mutableStateOf(false) }
    var showRemotePathDialog by remember { mutableStateOf(false) }
    var showAuthMethodDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showPublicKeyDialog by remember { mutableStateOf(false) }
    var showRegenerateKeyDialog by remember { mutableStateOf(false) }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportIncludeCredentials by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImportConfig by remember { mutableStateOf<ConfigExport?>(null) }
    var importPassphrase by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setSyncModeBEnabled(true)
    }
    val runConnectionTest = rememberWithLocalNetworkPermission { viewModel.testConnection() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val includeCredentials = exportIncludeCredentials
        val passphrase = exportPassphrase.ifBlank { null }
        exportPassphrase = ""
        scope.launch {
            val jsonText = viewModel.exportConfigJson(includeCredentials, passphrase)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(jsonText.toByteArray(Charsets.UTF_8))
                }
            }.onFailure { importError = "Export failed: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) {
                importError = "Could not read the selected file"
                return@launch
            }
            viewModel.parseImportedConfig(text)
                .onSuccess { pendingImportConfig = it }
                .onFailure { importError = it.message ?: "Invalid configuration file" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(title = { Text("Settings") })

        SectionHeader("Sync Server")
        ListItem(
            headlineContent = { Text("Server host") },
            supportingContent = {
                Text(settings.serverHost.ifEmpty { "Not configured" })
            },
            modifier = Modifier.clickable { showHostDialog = true },
        )
        ListItem(
            headlineContent = { Text("Server port") },
            supportingContent = { Text(settings.serverPort.toString()) },
            modifier = Modifier.clickable { showPortDialog = true },
        )
        ListItem(
            headlineContent = { Text("Pinned host key") },
            supportingContent = {
                Text(
                    if (settings.pinnedHostKey.isEmpty()) {
                        "Not pinned — trusted on first connect (TOFU)"
                    } else {
                        settings.pinnedHostKey
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.clickable(enabled = settings.pinnedHostKey.isNotEmpty()) {
                showClearKeyDialog = true
            },
        )
        ListItem(
            headlineContent = { Text("Test connection") },
            supportingContent = { Text(connectionTestLabel(connectionTest)) },
            trailingContent = {
                if (connectionTest == ConnectionTest.Running) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            },
            modifier = Modifier.clickable { runConnectionTest() },
        )

        HorizontalDivider()
        SectionHeader("Sync account")
        ListItem(
            headlineContent = { Text("Username") },
            supportingContent = { Text(settings.serverUser.ifEmpty { "Not configured" }) },
            modifier = Modifier.clickable { showUserDialog = true },
        )
        ListItem(
            headlineContent = { Text("Remote music folder") },
            supportingContent = {
                Text(settings.remoteBasePath.ifEmpty { "Not configured — e.g. /srv/music" })
            },
            modifier = Modifier.clickable { showRemotePathDialog = true },
        )
        ListItem(
            headlineContent = { Text("Authentication") },
            supportingContent = {
                Text(
                    when (settings.authMethod) {
                        AuthMethod.PASSWORD -> "Password"
                        AuthMethod.PUBLIC_KEY -> "SSH key (Ed25519)"
                    }
                )
            },
            modifier = Modifier.clickable { showAuthMethodDialog = true },
        )
        if (settings.authMethod == AuthMethod.PASSWORD) {
            ListItem(
                headlineContent = { Text("Password") },
                supportingContent = {
                    Text(if (settings.hasPassword) "Set (stored encrypted)" else "Not set")
                },
                modifier = Modifier.clickable { showPasswordDialog = true },
            )
        } else {
            ListItem(
                headlineContent = { Text("SSH key") },
                supportingContent = {
                    Text(
                        if (settings.hasSshKey) {
                            "Generated — tap to show the public key"
                        } else {
                            "Not generated yet — tap to generate"
                        }
                    )
                },
                modifier = Modifier.clickable {
                    if (settings.hasSshKey) showPublicKeyDialog = true
                    else viewModel.generateSshKey()
                },
            )
            if (settings.hasSshKey) {
                ListItem(
                    headlineContent = { Text("Regenerate SSH key") },
                    supportingContent = { Text("Replaces the key — update authorized_keys on the server") },
                    modifier = Modifier.clickable { showRegenerateKeyDialog = true },
                )
            }
        }
        ListItem(
            headlineContent = { Text("Automatic daily sync") },
            supportingContent = { Text("Background sync — never deletes without confirmation") },
            trailingContent = {
                Switch(
                    checked = settings.autoSyncEnabled,
                    onCheckedChange = { viewModel.setAutoSyncEnabled(it) },
                )
            },
        )

        HorizontalDivider()
        SectionHeader("Network")
        ListItem(
            headlineContent = { Text("Require VPN outside home network") },
            supportingContent = { Text("Block sync unless WireGuard is active") },
            trailingContent = {
                Switch(
                    checked = settings.requireVpnOutsideHome,
                    onCheckedChange = { viewModel.setRequireVpnOutsideHome(it) },
                )
            },
        )
        ListItem(
            headlineContent = { Text("Detect home network by Wi-Fi name") },
            supportingContent = { Text("Mode B — requires location permission") },
            trailingContent = {
                Switch(
                    checked = settings.syncModeBEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) showModeBDisclosure = true
                        else viewModel.setSyncModeBEnabled(false)
                    },
                )
            },
        )

        if (settings.syncModeBEnabled) {
            SectionHeader("Home Wi-Fi networks")
            settings.homeSsids.sorted().forEach { ssid ->
                ListItem(
                    headlineContent = { Text(ssid) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeHomeSsid(ssid) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove $ssid")
                        }
                    },
                )
            }
            ListItem(
                headlineContent = { Text("Add network") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable { showAddSsidDialog = true },
            )
        }

        HorizontalDivider()
        SectionHeader("Backup")
        ListItem(
            headlineContent = { Text("Export configuration") },
            supportingContent = { Text("Server, network and library settings as JSON") },
            modifier = Modifier.clickable { showExportDialog = true },
        )
        ListItem(
            headlineContent = { Text("Import configuration") },
            supportingContent = { Text("Restore settings from a previously exported file") },
            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json")) },
        )

        HorizontalDivider()
        SectionHeader("Debug log")
        ListItem(
            headlineContent = { Text("Enable debug logging") },
            supportingContent = {
                Text("Covers sync, network, player and equalizer in detail. Normal logs are always kept.")
            },
            trailingContent = {
                Switch(
                    checked = settings.debugLogEnabled,
                    onCheckedChange = { viewModel.setDebugLogEnabled(it) },
                )
            },
        )
        ListItem(
            headlineContent = { Text("View log") },
            supportingContent = { Text("Inspect, copy or share the buffered log") },
            modifier = Modifier.clickable { onNavigateToDebugLog() },
        )

        HorizontalDivider()
        SectionHeader("About")
        ListItem(
            headlineContent = { Text("MukkeKlopper") },
            supportingContent = { Text("Version 1.0.0") },
        )
    }

    if (showHostDialog) {
        TextFieldDialog(
            title = "Server host",
            initialValue = settings.serverHost,
            placeholder = "e.g. 192.168.1.10 or sync.example.org",
            onConfirm = { viewModel.setServerHost(it) },
            onDismiss = { showHostDialog = false },
        )
    }

    if (showPortDialog) {
        TextFieldDialog(
            title = "Server port",
            initialValue = settings.serverPort.toString(),
            placeholder = "22",
            validate = { it.toIntOrNull() in 1..65535 },
            onConfirm = { viewModel.setServerPort(it.toInt()) },
            onDismiss = { showPortDialog = false },
        )
    }

    if (showClearKeyDialog) {
        AlertDialog(
            onDismissRequest = { showClearKeyDialog = false },
            title = { Text("Forget pinned host key?") },
            text = {
                Text(
                    "The server's identity will be trusted again on the next " +
                        "connection. Only do this if you changed the server's SSH key."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearPinnedHostKey()
                    showClearKeyDialog = false
                }) { Text("Forget key") }
            },
            dismissButton = {
                TextButton(onClick = { showClearKeyDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showModeBDisclosure) {
        // Prominent disclosure required by Play policy before requesting location.
        AlertDialog(
            onDismissRequest = { showModeBDisclosure = false },
            title = { Text("Location permission needed") },
            text = {
                Text(
                    "MukkeKlopper uses your device's location permission only to read " +
                        "the name (SSID) of the connected Wi-Fi network, so sync can " +
                        "detect when you are on your home network. Android requires " +
                        "location permission to access the Wi-Fi name. Your location " +
                        "is never stored or shared."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showModeBDisclosure = false
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.setSyncModeBEnabled(true)
                    else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showModeBDisclosure = false }) { Text("Cancel") }
            },
        )
    }

    if (showAddSsidDialog) {
        TextFieldDialog(
            title = "Add home Wi-Fi network",
            initialValue = "",
            placeholder = "Network name (SSID)",
            validate = { it.isNotBlank() },
            onConfirm = { viewModel.addHomeSsid(it) },
            onDismiss = { showAddSsidDialog = false },
        )
    }

    if (showUserDialog) {
        TextFieldDialog(
            title = "Username",
            initialValue = settings.serverUser,
            placeholder = "SSH user on the server",
            onConfirm = { viewModel.setServerUser(it) },
            onDismiss = { showUserDialog = false },
        )
    }

    if (showRemotePathDialog) {
        TextFieldDialog(
            title = "Remote music folder",
            initialValue = settings.remoteBasePath,
            placeholder = "e.g. /srv/music",
            validate = { it.isNotBlank() },
            onConfirm = { viewModel.setRemoteBasePath(it) },
            onDismiss = { showRemotePathDialog = false },
        )
    }

    if (showAuthMethodDialog) {
        AlertDialog(
            onDismissRequest = { showAuthMethodDialog = false },
            title = { Text("Authentication method") },
            text = {
                Column {
                    AuthMethodOption(
                        label = "Password",
                        description = "Stored encrypted on this device",
                        selected = settings.authMethod == AuthMethod.PASSWORD,
                        onClick = {
                            viewModel.setAuthMethod(AuthMethod.PASSWORD)
                            showAuthMethodDialog = false
                        },
                    )
                    AuthMethodOption(
                        label = "SSH key (Ed25519)",
                        description = "Generated in-app; add the public key to authorized_keys",
                        selected = settings.authMethod == AuthMethod.PUBLIC_KEY,
                        onClick = {
                            viewModel.setAuthMethod(AuthMethod.PUBLIC_KEY)
                            showAuthMethodDialog = false
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAuthMethodDialog = false }) { Text("Close") }
            },
        )
    }

    if (showPasswordDialog) {
        TextFieldDialog(
            title = "Server password",
            initialValue = "",
            placeholder = if (settings.hasPassword) "Leave empty to remove" else "Password",
            password = true,
            onConfirm = { viewModel.setPassword(it) },
            onDismiss = { showPasswordDialog = false },
        )
    }

    if (showPublicKeyDialog) {
        AlertDialog(
            onDismissRequest = { showPublicKeyDialog = false },
            title = { Text("Public key") },
            text = {
                Column {
                    Text(
                        "Add this line to ~/.ssh/authorized_keys of the sync user on the server:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        sshPublicKey ?: "No key available",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = sshPublicKey != null,
                    onClick = {
                        sshPublicKey?.let { clipboard.setText(AnnotatedString(it)) }
                        showPublicKeyDialog = false
                    },
                ) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showPublicKeyDialog = false }) { Text("Close") }
            },
        )
    }

    if (showRegenerateKeyDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateKeyDialog = false },
            title = { Text("Regenerate SSH key?") },
            text = {
                Text(
                    "The current key will be replaced and sync will fail until you add " +
                        "the new public key to the server's authorized_keys."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.generateSshKey()
                    showRegenerateKeyDialog = false
                    showPublicKeyDialog = true
                }) { Text("Regenerate") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateKeyDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export configuration") },
            text = {
                Column {
                    Text(
                        "Server, network and library settings will be exported as JSON.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { exportIncludeCredentials = !exportIncludeCredentials },
                    ) {
                        Switch(
                            checked = exportIncludeCredentials,
                            onCheckedChange = { exportIncludeCredentials = it },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Include credentials (password / SSH key)")
                    }
                    if (exportIncludeCredentials) {
                        Text(
                            "The file will contain your password or SSH key, protected only " +
                                "by the passphrase below. Keep it safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedTextField(
                            value = exportPassphrase,
                            onValueChange = { exportPassphrase = it },
                            placeholder = { Text("Passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !exportIncludeCredentials || exportPassphrase.isNotBlank(),
                    onClick = {
                        showExportDialog = false
                        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
                        exportLauncher.launch("mukkeklopper-config-$dateStr.json")
                    },
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import failed") },
            text = { Text(importError.orEmpty()) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }

    pendingImportConfig?.let { config ->
        AlertDialog(
            onDismissRequest = {
                pendingImportConfig = null
                importPassphrase = ""
            },
            title = { Text("Import this configuration?") },
            text = {
                Column {
                    Text("Host: ${config.server.host.ifBlank { "—" }}:${config.server.port}")
                    Text("User: ${config.server.user.ifBlank { "—" }}")
                    Text("Remote folder: ${config.server.remoteBasePath.ifBlank { "—" }}")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (config.secrets != null) "Includes encrypted credentials"
                        else "No credentials included — you will need to set them again",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (config.secrets != null) {
                        OutlinedTextField(
                            value = importPassphrase,
                            onValueChange = { importPassphrase = it },
                            placeholder = { Text("Passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val passphrase = importPassphrase.ifBlank { null }
                    scope.launch {
                        viewModel.applyImportedConfig(config, passphrase)
                            .onFailure { importError = "Import failed: ${it.message}" }
                        pendingImportConfig = null
                        importPassphrase = ""
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportConfig = null
                    importPassphrase = ""
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AuthMethodOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validate: (String) -> Boolean = { true },
    password: Boolean = false,
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                // Hosts, Pfade und Benutzernamen sind case-sensitiv — Autokorrektur und
                // Auto-Capitalize der Tastatur würden sie verfälschen (/home → /Home)
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                visualTransformation =
                    if (password) PasswordVisualTransformation()
                    else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = validate(value),
                onClick = {
                    onConfirm(value)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun connectionTestLabel(test: ConnectionTest): String = when (test) {
    ConnectionTest.Idle -> "Run the network gate check against the sync server"
    ConnectionTest.Running -> "Checking network and host key…"
    is ConnectionTest.Done -> when (val d = test.decision) {
        SyncDecision.Allowed -> "OK — host key verified, sync allowed"
        SyncDecision.NotConfigured -> "Set a server host first"
        SyncDecision.Blocked -> "Blocked — not on home network and no VPN active"
        is SyncDecision.MitmWarning ->
            "HOST KEY MISMATCH — possible MITM! Pinned key was NOT changed."
        is SyncDecision.Unreachable -> "Server unreachable: ${d.reason}"
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
