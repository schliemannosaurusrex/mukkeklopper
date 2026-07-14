package de.schliemannosaurusrex.kaniamp.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.schliemannosaurusrex.kaniamp.settings.AuthMethod
import de.schliemannosaurusrex.kaniamp.settings.KaniSettings
import de.schliemannosaurusrex.kaniamp.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.IOException
import java.io.OutputStream
import java.security.PublicKey

class SyncConfigException(message: String) : Exception(message)

/**
 * SSH-Sync-Engine (PLAN.md Phase 4): Pull-only-Delta-Sync vom Server nach
 * `Music/KaniAmp/<Server-Struktur>` via MediaStore. Der Aufrufer (SyncManager)
 * hat das Network-Gate bereits passiert; hier wird der Host-Key trotzdem
 * strikt gegen den gepinnten Fingerprint geprüft.
 */
class SyncEngine(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext

    data class RemoteTrack(val relPath: String, val size: Long, val mtimeSec: Long)
    data class LocalTrack(val relPath: String, val uri: Uri, val size: Long, val mtimeSec: Long)

    suspend fun sync(
        interactive: Boolean,
        onState: (SyncState) -> Unit,
        confirmDeletions: suspend (List<String>) -> Boolean,
    ): SyncState.Finished = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        if (settings.serverUser.isBlank()) throw SyncConfigException("Server user not configured")
        if (settings.remoteBasePath.isBlank()) throw SyncConfigException("Remote folder not configured")
        if (settings.pinnedHostKey.isEmpty()) {
            throw SyncConfigException("No pinned host key — run \"Test connection\" in Settings first")
        }

        onState(SyncState.Connecting)
        val client = SSHClient()
        try {
            client.connectTimeout = SSH_TIMEOUT_MS
            client.timeout = SSH_TIMEOUT_MS
            client.addHostKeyVerifier(PinnedKeyVerifier(settings.pinnedHostKey))
            client.connect(settings.serverHost, settings.serverPort)
            authenticate(client, settings)

            client.newSFTPClient().use { sftp ->
                onState(SyncState.Indexing)
                val remote = listRemote(sftp, settings.remoteBasePath)
                val local = listLocal()

                val toDownload = remote.filter { r ->
                    val l = local[r.relPath]
                    l == null || l.size != r.size || r.mtimeSec > l.mtimeSec
                }
                val stale = local.keys - remote.map { it.relPath }.toSet()

                var downloaded = 0
                var failed = 0
                toDownload.forEachIndexed { index, track ->
                    currentCoroutineContext().ensureActive()
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
                        download(sftp, settings.remoteBasePath, track, local[track.relPath]) { done ->
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
                        ok.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                        failed++
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

                SyncState.Finished(
                    downloaded = downloaded,
                    skipped = remote.size - toDownload.size,
                    deleted = deleted,
                    failed = failed,
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

    private suspend fun authenticate(client: SSHClient, settings: KaniSettings) {
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

    // --- Lokale Inventur (MediaStore unter Music/KaniAmp/) ---------------

    private fun listLocal(): Map<String, LocalTrack> {
        val result = mutableMapOf<String, LocalTrack>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
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

            while (cursor.moveToNext()) {
                val relDir = (cursor.getString(pathCol) ?: continue).removePrefix(SYNC_ROOT)
                val name = cursor.getString(nameCol) ?: continue
                val rel = relDir + name
                result[rel] = LocalTrack(
                    relPath = rel,
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    size = cursor.getLong(sizeCol),
                    mtimeSec = cursor.getLong(mtimeCol),
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

        val isNew = existing == null
        val uri: Uri = if (existing != null) {
            resolver.update(existing.uri, pending, null, null)
            existing.uri
        } else {
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
        const val SYNC_ROOT = "Music/KaniAmp/"
        const val SSH_TIMEOUT_MS = 10_000
        const val BUFFER_SIZE = 32 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
