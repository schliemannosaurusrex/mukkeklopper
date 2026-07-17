package de.schliemannosaurusrex.mukkeklopper.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.network.NetworkGatekeeper
import de.schliemannosaurusrex.mukkeklopper.network.SyncDecision
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zentraler Sync-Koordinator: hält den [SyncState] als Prozess-Singleton, damit
 * UI (SyncViewModel), Foreground-Service und WorkManager-Worker denselben Lauf
 * beobachten. Es läuft höchstens ein Sync gleichzeitig.
 */
object SyncManager {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    @Volatile
    private var deletionDecision: CompletableDeferred<Boolean>? = null
    @Volatile
    private var writeAccessDecision: CompletableDeferred<Boolean>? = null
    @Volatile
    private var cancelRequested = false

    val isRunning: Boolean get() = running.get()

    /** UI-Einstieg: startet den Foreground-Service, der [runSync] ausführt. */
    fun requestStart(context: Context, retryOnly: List<String>? = null) {
        if (running.get()) return
        val intent = Intent(context, MukkeSyncService::class.java)
        retryOnly?.let { intent.putStringArrayListExtra(EXTRA_RETRY_PATHS, ArrayList(it)) }
        context.startForegroundService(intent)
    }

    fun cancel() {
        cancelRequested = true
        deletionDecision?.cancel()
        writeAccessDecision?.cancel()
    }

    fun confirmDeletions(delete: Boolean) {
        deletionDecision?.complete(delete)
    }

    /** Ergebnis des System-Write-Grant-Dialogs (RESULT_OK = erteilt). */
    fun confirmWriteAccess(granted: Boolean) {
        writeAccessDecision?.complete(granted)
    }

    fun clearResult() {
        if (!running.get()) _state.value = SyncState.Idle
    }

    /** Führt einen kompletten Sync-Lauf aus. Gate-Check inklusive. */
    suspend fun runSync(context: Context, interactive: Boolean, retryOnly: List<String>? = null) {
        if (!running.compareAndSet(false, true)) return
        cancelRequested = false
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        AppLog.i(TAG, "runSync start: interactive=$interactive retryOnly=${retryOnly?.size ?: "all"}")
        try {
            _state.value = SyncState.CheckingNetwork
            val decision = NetworkGatekeeper(appContext, repository).canSync()
            if (decision != SyncDecision.Allowed) {
                AppLog.i(TAG, "sync gate denied: $decision")
                _state.value = SyncState.Blocked(decision)
                return
            }

            val engine = SyncEngine(appContext, repository)
            val result = engine.sync(
                interactive = interactive,
                retryOnly = retryOnly?.toSet(),
                onState = { newState ->
                    if (cancelRequested) throw CancellationException("Sync cancelled")
                    _state.value = newState
                },
                confirmDeletions = ::awaitDeletionDecision,
                requestWriteAccess = { uris -> awaitWriteAccessDecision(appContext, uris) },
            )
            _state.value = result
            runCatching { repository.setLastSyncReport(result.failures) }
        } catch (e: CancellationException) {
            AppLog.i(TAG, "sync cancelled")
            _state.value = SyncState.Idle
            // Nur weiterwerfen, wenn wirklich der äußere Job gecancelt wurde —
            // beim User-Abbruch (cancelRequested) muss der Aufrufer normal
            // weiterlaufen, damit der Service stopSelf() erreicht.
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: SyncConfigException) {
            AppLog.e(TAG, "sync failed (config/auth): ${e.message}")
            _state.value = SyncState.Failed(e.message ?: "Sync not configured")
        } catch (e: Exception) {
            AppLog.e(TAG, "sync failed", e)
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            deletionDecision = null
            writeAccessDecision = null
            running.set(false)
        }
    }

    private suspend fun awaitDeletionDecision(paths: List<String>): Boolean {
        val decision = CompletableDeferred<Boolean>()
        deletionDecision = decision
        _state.value = SyncState.ConfirmDeletions(paths)
        return try {
            decision.await()
        } finally {
            deletionDecision = null
        }
    }

    /**
     * Holt den User-Write-Grant für fremd-eigene MediaStore-Einträge ein: publiziert
     * den [android.provider.MediaStore.createWriteRequest]-IntentSender als State,
     * die Sync-UI startet den System-Dialog und meldet das Ergebnis über
     * [confirmWriteAccess] zurück. Ohne sichtbare UI (Auto-Sync) ruft die Engine
     * diese Funktion nicht auf.
     */
    private suspend fun awaitWriteAccessDecision(context: Context, uris: List<Uri>): Boolean {
        val request = runCatching {
            MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
        }.getOrElse { e ->
            AppLog.w(TAG, "createWriteRequest failed", e)
            return false
        }
        val decision = CompletableDeferred<Boolean>()
        writeAccessDecision = decision
        _state.value = SyncState.AwaitWriteAccess(request)
        return try {
            decision.await()
        } finally {
            writeAccessDecision = null
        }
    }

    private const val TAG = "MukkeSync"
    const val EXTRA_RETRY_PATHS = "retry_paths"
}
