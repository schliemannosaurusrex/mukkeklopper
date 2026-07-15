package de.schliemannosaurusrex.mukkeklopper.sync

import kotlinx.serialization.Serializable
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import java.io.IOException

/** Ursache eines fehlgeschlagenen Datei-Downloads (PLAN.md Phase 4b). */
@Serializable
enum class FailureReason {
    PERMISSION_DENIED,
    NOT_FOUND,
    CONNECTION_LOST,
    OUT_OF_SPACE,
    MEDIASTORE_REJECTED,
    UNKNOWN,
}

/** Eine einzelne fehlgeschlagene Datei aus dem letzten Sync-Lauf. */
@Serializable
data class SyncFailure(
    val relPath: String,
    val reason: FailureReason,
    val detail: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val timestamp: Long,
)

/** Mapping der sshj-/IO-Exceptions auf [FailureReason] (PLAN.md Phase 4b). */
fun classifyFailure(e: Throwable): FailureReason = when {
    e is SFTPException -> when (e.statusCode) {
        Response.StatusCode.PERMISSION_DENIED -> FailureReason.PERMISSION_DENIED
        Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> FailureReason.NOT_FOUND
        Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> FailureReason.OUT_OF_SPACE
        Response.StatusCode.CONNECITON_LOST, Response.StatusCode.NO_CONNECTION -> FailureReason.CONNECTION_LOST
        else -> FailureReason.UNKNOWN
    }
    e is IOException && e.message.orEmpty().contains("MediaStore", ignoreCase = true) ->
        FailureReason.MEDIASTORE_REJECTED
    e is IOException && (
        e.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
            e.message.orEmpty().contains("No space left", ignoreCase = true)
        ) -> FailureReason.OUT_OF_SPACE
    e is IOException -> FailureReason.CONNECTION_LOST
    else -> FailureReason.UNKNOWN
}

/** Klartext-Grund für die UI (Fehler-Detail-Screen, Copy-Report). */
fun FailureReason.displayText(): String = when (this) {
    FailureReason.PERMISSION_DENIED -> "Permission denied on server"
    FailureReason.NOT_FOUND -> "File not found on server (removed during sync)"
    FailureReason.CONNECTION_LOST -> "Connection lost"
    FailureReason.OUT_OF_SPACE -> "Not enough storage space on device"
    FailureReason.MEDIASTORE_REJECTED -> "Device storage rejected the file"
    FailureReason.UNKNOWN -> "Unknown error"
}
