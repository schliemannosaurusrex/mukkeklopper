package de.schliemannosaurusrex.kaniamp.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import de.schliemannosaurusrex.kaniamp.network.NetworkGatekeeper
import de.schliemannosaurusrex.kaniamp.network.SyncDecision
import de.schliemannosaurusrex.kaniamp.settings.SettingsRepository
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
    private var cancelRequested = false

    val isRunning: Boolean get() = running.get()

    /** UI-Einstieg: startet den Foreground-Service, der [runSync] ausführt. */
    fun requestStart(context: Context) {
        if (running.get()) return
        context.startForegroundService(Intent(context, KaniSyncService::class.java))
    }

    fun cancel() {
        cancelRequested = true
        deletionDecision?.cancel()
    }

    fun confirmDeletions(delete: Boolean) {
        deletionDecision?.complete(delete)
    }

    fun clearResult() {
        if (!running.get()) _state.value = SyncState.Idle
    }

    /** Führt einen kompletten Sync-Lauf aus. Gate-Check inklusive. */
    suspend fun runSync(context: Context, interactive: Boolean) {
        if (!running.compareAndSet(false, true)) return
        cancelRequested = false
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        try {
            _state.value = SyncState.CheckingNetwork
            val decision = NetworkGatekeeper(appContext, repository).canSync()
            if (decision != SyncDecision.Allowed) {
                _state.value = SyncState.Blocked(decision)
                return
            }

            val engine = SyncEngine(appContext, repository)
            _state.value = engine.sync(
                interactive = interactive,
                onState = { newState ->
                    if (cancelRequested) throw CancellationException("Sync cancelled")
                    _state.value = newState
                },
                confirmDeletions = ::awaitDeletionDecision,
            )
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            // Nur weiterwerfen, wenn wirklich der äußere Job gecancelt wurde —
            // beim User-Abbruch (cancelRequested) muss der Aufrufer normal
            // weiterlaufen, damit der Service stopSelf() erreicht.
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: SyncConfigException) {
            _state.value = SyncState.Failed(e.message ?: "Sync not configured")
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            deletionDecision = null
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

    private const val TAG = "KaniSync"
}
