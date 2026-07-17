package de.schliemannosaurusrex.mukkeklopper.sync

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schliemannosaurusrex.mukkeklopper.settings.MukkeSettings
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val state: StateFlow<SyncState> = SyncManager.state

    val settings: StateFlow<MukkeSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MukkeSettings(),
    )

    /** Fehler des letzten Sync-Laufs, überlebt App-Neustart (nur der letzte Lauf). */
    val lastFailures: StateFlow<List<SyncFailure>> = repository.lastSyncReport.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun startSync() = SyncManager.requestStart(getApplication())

    /** Startet einen Sync, der nur die übergebenen Pfade erneut versucht. */
    fun retryFailed(paths: List<String>) = SyncManager.requestStart(getApplication(), retryOnly = paths)

    fun cancelSync() = SyncManager.cancel()

    fun confirmDeletions(delete: Boolean) = SyncManager.confirmDeletions(delete)

    /** Ergebnis des System-Write-Grant-Dialogs für fremd-eigene Dateien. */
    fun confirmWriteAccess(granted: Boolean) = SyncManager.confirmWriteAccess(granted)

    fun dismissResult() = SyncManager.clearResult()

    /** Öffnet die WireGuard-App bzw. deren Play-Store-Seite (Blocked-Banner). */
    fun openWireGuard() {
        val app: Application = getApplication()
        val launch = app.packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$WIREGUARD_PACKAGE"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(launch) }
    }

    private companion object {
        const val WIREGUARD_PACKAGE = "com.wireguard.android"
    }
}
