package de.schliemannosaurusrex.kaniamp.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schliemannosaurusrex.kaniamp.network.NetworkGatekeeper
import de.schliemannosaurusrex.kaniamp.network.SyncDecision
import de.schliemannosaurusrex.kaniamp.sync.SshKeys
import de.schliemannosaurusrex.kaniamp.sync.SyncWorker
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

    val settings: StateFlow<KaniSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = KaniSettings(),
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

    private suspend fun refreshSshPublicKey() {
        _sshPublicKey.value = repository.getSshSeed()?.let { SshKeys.openSshPublicKey(it) }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
