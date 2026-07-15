package de.schliemannosaurusrex.mukkeklopper.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schliemannosaurusrex.mukkeklopper.network.NetworkGatekeeper
import de.schliemannosaurusrex.mukkeklopper.network.SyncDecision
import de.schliemannosaurusrex.mukkeklopper.sync.SshKeys
import de.schliemannosaurusrex.mukkeklopper.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Done(val decision: SyncDecision) : ConnectionTest
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val gatekeeper = NetworkGatekeeper(application, repository)

    val settings: StateFlow<MukkeSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MukkeSettings(),
    )

    private val _connectionTest = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val connectionTest: StateFlow<ConnectionTest> = _connectionTest.asStateFlow()

    /** OpenSSH-Zeile des generierten Public Keys — für Anzeige/Kopieren in den Settings. */
    private val _sshPublicKey = MutableStateFlow<String?>(null)
    val sshPublicKey: StateFlow<String?> = _sshPublicKey.asStateFlow()

    init {
        launch { refreshSshPublicKey() }
    }

    fun testConnection() {
        if (_connectionTest.value == ConnectionTest.Running) return
        viewModelScope.launch {
            _connectionTest.value = ConnectionTest.Running
            _connectionTest.value = ConnectionTest.Done(gatekeeper.canSync())
        }
    }

    fun setServerHost(host: String) = launch { repository.setServerHost(host) }

    fun setServerPort(port: Int) = launch { repository.setServerPort(port) }

    fun clearPinnedHostKey() = launch { repository.clearPinnedHostKey() }

    fun setRequireVpnOutsideHome(enabled: Boolean) =
        launch { repository.setRequireVpnOutsideHome(enabled) }

    fun setSyncModeBEnabled(enabled: Boolean) =
        launch { repository.setSyncModeBEnabled(enabled) }

    fun addHomeSsid(ssid: String) = launch { repository.addHomeSsid(ssid) }

    fun removeHomeSsid(ssid: String) = launch { repository.removeHomeSsid(ssid) }

    fun setServerUser(user: String) = launch { repository.setServerUser(user) }

    fun setRemoteBasePath(path: String) = launch { repository.setRemoteBasePath(path) }

    fun setAuthMethod(method: AuthMethod) = launch { repository.setAuthMethod(method) }

    fun setPassword(password: String) = launch {
        if (password.isEmpty()) repository.clearPassword() else repository.setPassword(password)
    }

    fun generateSshKey() = launch {
        repository.setSshSeed(SshKeys.generateSeed())
        refreshSshPublicKey()
    }

    fun setAutoSyncEnabled(enabled: Boolean) = launch {
        repository.setAutoSyncEnabled(enabled)
        SyncWorker.setEnabled(getApplication(), enabled)
    }

    fun setDebugLogEnabled(enabled: Boolean) = launch { repository.setDebugLogEnabled(enabled) }

    private suspend fun refreshSshPublicKey() {
        _sshPublicKey.value = repository.getSshSeed()?.let { SshKeys.openSshPublicKey(it) }
    }

    /** Baut das Export-JSON (PLAN.md Phase 4b „Backup"); der Aufrufer schreibt es in die Ziel-Uri. */
    suspend fun exportConfigJson(includeCredentials: Boolean, passphrase: String?): String =
        ConfigExporter.export(repository, includeCredentials, passphrase)

    /** Parst eine importierte Datei, ohne sie schon anzuwenden (für den Bestätigungsdialog). */
    fun parseImportedConfig(text: String): Result<ConfigExport> = runCatching { ConfigExporter.parse(text) }

    /** Wendet einen zuvor geparsten Import an (nach Nutzerbestätigung). */
    suspend fun applyImportedConfig(config: ConfigExport, passphrase: String?): Result<Unit> = runCatching {
        ConfigExporter.apply(repository, config, passphrase)
        SyncWorker.setEnabled(getApplication(), config.sync.autoSyncEnabled)
        refreshSshPublicKey()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
