package de.schliemannosaurusrex.kaniamp.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground-Service (dataSync), der einen manuellen Sync-Lauf trägt. */
class KaniSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SyncManager.cancel()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing sync…"))

        if (!SyncManager.isRunning) {
            scope.launch {
                SyncManager.runSync(applicationContext, interactive = true)
                stopSelf()
            }
            scope.launch {
                SyncManager.state.collect { state -> notifyState(state) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notifyState(state: SyncState) {
        val text = when (state) {
            SyncState.CheckingNetwork -> "Checking network…"
            SyncState.Connecting -> "Connecting to server…"
            SyncState.Indexing -> "Comparing files…"
            is SyncState.Downloading ->
                "Downloading ${state.fileIndex}/${state.fileCount}: ${state.fileName}"
            is SyncState.ConfirmDeletions -> "Waiting for delete confirmation"
            else -> return
        }
        val progress = (state as? SyncState.Downloading)?.let { d ->
            if (d.bytesTotal > 0) ((d.bytesDone * 100) / d.bytesTotal).toInt() else null
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int? = null): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, KaniSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("KaniAmp sync")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (progress != null) setProgress(100, progress, false) }
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "sync"
        const val NOTIFICATION_ID = 2
        const val ACTION_CANCEL = "de.schliemannosaurusrex.kaniamp.sync.CANCEL"
    }
}
