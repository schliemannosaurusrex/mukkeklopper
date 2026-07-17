package de.schliemannosaurusrex.mukkeklopper.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.settings.AuthMethod
import de.schliemannosaurusrex.mukkeklopper.settings.MukkeSettings
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.io.OutputStream
import java.security.PublicKey

class SyncConfigException(message: String) : Exception(message)

/**
 * SSH-Sync-Engine (PLAN.md Phase 4): Pull-only-Delta-Sync vom Server nach
 * `Music/MukkeKlopper/<Server-Struktur>` via MediaStore. Der Aufrufer (SyncManager)
 * hat das Network-Gate bereits passiert; hier wird der Host-Key trotzdem
 * strikt gegen den gepinnten Fingerprint geprüft.
 */
class SyncEngine(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext

    data class RemoteTrack(val relPath: String, val size: Long, val mtimeSec: Long)
    data class LocalTrack(
        val relPath: String,
        val uri: Uri,
        val size: Long,
        val mtimeSec: Long,
        val isOwnedByApp: Boolean,
    )

    suspend fun sync(
        interactive: Boolean,
        onState: (SyncState) -> Unit,
        confirmDeletions: suspend (List<String>) -> Boolean,
        requestWriteAccess: suspend (List<Uri>) -> Boolean,
        retryOnly: Set<String>? = null,
    ): SyncState.Finished = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        if (settings.serverUser.isBlank()) throw SyncConfigException("Server user not configured")
        if (settings.remoteBasePath.isBlank()) throw SyncConfigException("Remote folder not configured")
        if (settings.pinnedHostKey.isEmpty()) {
            throw SyncConfigException("No pinned host key — run \"Test connection\" in Settings first")
        }

        AppLog.d(TAG, "sync start: interactive=$interactive retryOnly=${retryOnly?.size ?: "all"}")
        onState(SyncState.Connecting)
        val client = SSHClient()
        try {
            client.connectTimeout = SSH_TIMEOUT_MS
            client.timeout = SSH_TIMEOUT_MS
            client.addHostKeyVerifier(PinnedKeyVerifier(settings.pinnedHostKey))
            client.connect(settings.serverHost, settings.serverPort)
            AppLog.d(TAG, "connected to ${settings.serverHost}:${settings.serverPort}")
            authenticate(client, settings)
            AppLog.d(TAG, "authenticated as '${settings.serverUser}' via ${settings.authMethod}")

            client.newSFTPClient().use { sftp ->
                onState(SyncState.Indexing)
                val remote = listRemote(sftp, settings.remoteBasePath)
                val local = listLocal()
                AppLog.d(TAG, "indexed: remote=${remote.size} local=${local.size}")

                val toDownload = if (retryOnly != null) {
                    remote.filter { it.relPath in retryOnly }
                } else {
                    remote.filter { r ->
                        val l = local[r.relPath]
                        l == null || l.size != r.size || r.mtimeSec > l.mtimeSec
                    }
                }
                // Löschungen nur bei einem vollständigen Lauf ermitteln — ein Retry
                // betrachtet nur die übergebenen Pfade, nicht den ganzen Bestand.
                val stale = if (retryOnly != null) emptySet() else local.keys - remote.map { it.relPath }.toSet()

                // Fremd-eigene Einträge (App nicht Owner, z. B. nach Neuinstallation) lassen
                // sich nur mit User-Write-Grant überschreiben/löschen. Ein Grant-Dialog
                // deckt alle betroffenen URIs ab; ohne Grant werden sie übersprungen.
                val foreignTargets = (
                    toDownload.mapNotNull { local[it.relPath] } +
                        stale.mapNotNull { local[it] }
                    ).filterNot { it.isOwnedByApp }
                val writeAccessGranted = if (foreignTargets.isEmpty()) {
                    true
                } else {
                    val granted = interactive && requestWriteAccess(foreignTargets.map { it.uri })
                    AppLog.i(
                        TAG,
                        "write access for ${foreignTargets.size} foreign entries: " +
                            "granted=$granted (interactive=$interactive)",
                    )
                    granted
                }

                var downloaded = 0
                val failures = mutableListOf<SyncFailure>()
                toDownload.forEachIndexed { index, track ->
                    currentCoroutineContext().ensureActive()
                    val existing = local[track.relPath]
                    if (existing != null && !existing.isOwnedByApp && !writeAccessGranted) {
                        failures += SyncFailure(
                            relPath = track.relPath,
                            reason = FailureReason.NOT_OWNED,
                            detail = "File is owned by another app and write access was not granted",
                            bytesDone = 0,
                            bytesTotal = track.size,
                            timestamp = System.currentTimeMillis(),
                        )
                        return@forEachIndexed
                    }
                    var lastBytesDone = 0L
                    onState(
                        SyncState.Downloading(
                            fileName = track.relPath.substringAfterLast('/'),
                            fileIndex = index + 1,
                            fileCount = toDownload.size,
                            bytesDone = 0,
                            bytesTotal = track.size,
                        )
                    )
                    val ok = runCatching {
                        download(sftp, settings.remoteBasePath, track, existing) { done ->
                            lastBytesDone = done
                            onState(
                                SyncState.Downloading(
                                    fileName = track.relPath.substringAfterLast('/'),
                                    fileIndex = index + 1,
                                    fileCount = toDownload.size,
                                    bytesDone = done,
                                    bytesTotal = track.size,
                                )
                            )
                        }
                    }
                    if (ok.isSuccess) downloaded++ else {
                        val error = ok.exceptionOrNull()
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        AppLog.w(TAG, "download failed: ${track.relPath}", error)
                        failures += SyncFailure(
                            relPath = track.relPath,
                            reason = classifyFailure(error ?: IOException("Unknown error")),
                            detail = error?.message ?: error?.javaClass?.simpleName ?: "Unknown error",
                            bytesDone = lastBytesDone,
                            bytesTotal = track.size,
                            timestamp = System.currentTimeMillis(),
                        )
                    }
                }

                var deleted = 0
                var deletionsPending = 0
                if (stale.isNotEmpty()) {
                    if (interactive && confirmDeletions(stale.sorted())) {
                        stale.forEach { rel ->
                            local[rel]?.let { track ->
                                runCatching { appContext.contentResolver.delete(track.uri, null, null) }
                                    .onSuccess { deleted++ }
                                    .onFailure { deletionsPending++ }
                            }
                        }
                    } else {
                        deletionsPending = stale.size
                    }
                }

                AppLog.i(
                    TAG,
                    "sync finished: downloaded=$downloaded skipped=${remote.size - toDownload.size} " +
                        "deleted=$deleted failed=${failures.size} deletionsPending=$deletionsPending",
                )
                SyncState.Finished(
                    downloaded = downloaded,
                    skipped = remote.size - toDownload.size,
                    deleted = deleted,
                    failures = failures,
                    deletionsPending = deletionsPending,
                )
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private class PinnedKeyVerifier(private val pinned: String) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
            SecurityUtils.getFingerprint(key) == pinned

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private suspend fun authenticate(client: SSHClient, settings: MukkeSettings) {
        try {
            when (settings.authMethod) {
                AuthMethod.PASSWORD -> {
                    val password = settingsRepository.getPassword()
                        ?: throw SyncConfigException("No password stored")
                    client.authPassword(settings.serverUser, password)
                }
                AuthMethod.PUBLIC_KEY -> {
                    val seed = settingsRepository.getSshSeed()
                        ?: throw SyncConfigException("No SSH key generated")
                    client.authPublickey(settings.serverUser, SshKeys.keyProviderFromSeed(seed))
                }
            }
        } catch (e: UserAuthException) {
            // sshj's message here ("Exhausted available authentication methods") never says
            // *why* — the actual reason (wrong credentials vs. method disabled server-side)
            // only shows up in sshj's own SLF4J debug log (see AppLog/MukkeSlf4jServiceProvider).
            AppLog.e(TAG, "authentication failed for '${settings.serverUser}' via ${settings.authMethod}", e)
            val hint = when (settings.authMethod) {
                AuthMethod.PASSWORD ->
                    "Check the password, or the server may not allow password authentication " +
                        "(PasswordAuthentication disabled) — try the SSH key method instead."
                AuthMethod.PUBLIC_KEY ->
                    "Check that the public key was added to the server's authorized_keys and " +
                        "that PubkeyAuthentication is enabled."
            }
            throw SyncConfigException(
                "Authentication failed for user '${settings.serverUser}' (${settings.authMethod.storageValue}). $hint " +
                    "Enable debug logging in Settings and retry for the exact server response."
            )
        }
    }

    // --- Remote-Inventur -------------------------------------------------

    private fun listRemote(sftp: SFTPClient, basePath: String): List<RemoteTrack> {
        val result = mutableListOf<RemoteTrack>()
        val base = basePath.trimEnd('/')

        fun walk(dir: String, relDir: String) {
            for (entry in sftp.ls(dir)) {
                val name = entry.name
                if (name == "." || name == "..") continue
                val rel = if (relDir.isEmpty()) name else "$relDir/$name"
                when {
                    entry.isDirectory -> walk("$dir/$name", rel)
                    entry.isRegularFile && isAudioFile(name) && ".." !in rel.split('/') ->
                        result += RemoteTrack(
                            relPath = rel,
                            size = entry.attributes.size,
                            mtimeSec = entry.attributes.mtime,
                        )
                }
            }
        }
        walk(base, "")
        return result
    }

    // --- Lokale Inventur (MediaStore unter Music/MukkeKlopper/) ---------------

    private fun listLocal(): Map<String, LocalTrack> {
        val result = mutableMapOf<String, LocalTrack>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.OWNER_PACKAGE_NAME,
        )
        appContext.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$SYNC_ROOT%"),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mtimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val ownerCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)

            while (cursor.moveToNext()) {
                val relDir = (cursor.getString(pathCol) ?: continue).removePrefix(SYNC_ROOT)
                val name = cursor.getString(nameCol) ?: continue
                val rel = relDir + name
                result[rel] = LocalTrack(
                    relPath = rel,
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    size = cursor.getLong(sizeCol),
                    mtimeSec = cursor.getLong(mtimeCol),
                    isOwnedByApp = cursor.getString(ownerCol) == appContext.packageName,
                )
            }
        }
        return result
    }

    // --- Download (IS_PENDING während des Schreibens) ---------------------

    private suspend fun download(
        sftp: SFTPClient,
        basePath: String,
        track: RemoteTrack,
        existing: LocalTrack?,
        onProgress: (Long) -> Unit,
    ) {
        val remotePath = basePath.trimEnd('/') + "/" + track.relPath
        val resolver = appContext.contentResolver
        val pending = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 1) }
        val ready = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }

        // Fremd-eigene Einträge nicht in-place updaten, sondern löschen und neu anlegen
        // (Self-Healing): der neue Eintrag gehört dann dieser App, künftige Syncs
        // kommen ohne Write-Grant aus. Der Grant dafür wurde vorab eingeholt.
        val isNew = existing == null || !existing.isOwnedByApp
        val uri: Uri = if (existing != null && existing.isOwnedByApp) {
            resolver.update(existing.uri, pending, null, null)
            existing.uri
        } else {
            if (existing != null) {
                resolver.delete(existing.uri, null, null)
            }
            val name = track.relPath.substringAfterLast('/')
            val subDir = track.relPath.substringBeforeLast('/', "")
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, if (subDir.isEmpty()) SYNC_ROOT else "$SYNC_ROOT$subDir/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                mimeTypeFor(name)?.let { put(MediaStore.Audio.Media.MIME_TYPE, it) }
            }
            resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore insert failed for ${track.relPath}")
        }

        try {
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot open output stream for ${track.relPath}")
            output.use { copyRemoteFile(sftp, remotePath, it, onProgress) }
            resolver.update(uri, ready, null, null)
        } catch (e: Exception) {
            if (isNew) runCatching { resolver.delete(uri, null, null) }
            else runCatching { resolver.update(uri, ready, null, null) }
            throw e
        }
    }

    private suspend fun copyRemoteFile(
        sftp: SFTPClient,
        remotePath: String,
        output: OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        sftp.open(remotePath).use { remoteFile ->
            remoteFile.RemoteFileInputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                var sinceLastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    sinceLastReport += read
                    if (sinceLastReport >= PROGRESS_STEP_BYTES) {
                        sinceLastReport = 0
                        onProgress(total)
                    }
                }
                output.flush()
            }
        }
    }

    private fun isAudioFile(name: String): Boolean =
        mimeTypeFor(name) != null

    private fun mimeTypeFor(name: String): String? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "m4a", "m4b" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/x-wav"
            "wma" -> "audio/x-ms-wma"
            "aif", "aiff" -> "audio/x-aiff"
            else -> null
        }

    private companion object {
        const val TAG = "MukkeSyncEngine"
        const val SYNC_ROOT = "Music/MukkeKlopper/"
        const val SSH_TIMEOUT_MS = 10_000
        const val BUFFER_SIZE = 32 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
