package de.schliemannosaurusrex.mukkeklopper.sync

import de.schliemannosaurusrex.mukkeklopper.network.SyncDecision

sealed interface SyncState {
    data object Idle : SyncState
    data object CheckingNetwork : SyncState
    data class Blocked(val decision: SyncDecision) : SyncState
    data object Connecting : SyncState
    data object Indexing : SyncState
    data class Downloading(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) : SyncState

    /** Server-seitig gelöschte Dateien — lokales Löschen wartet auf Bestätigung. */
    data class ConfirmDeletions(val paths: List<String>) : SyncState
    data class Finished(
        val downloaded: Int,
        val skipped: Int,
        val deleted: Int,
        val failures: List<SyncFailure> = emptyList(),
        val deletionsPending: Int = 0,
    ) : SyncState

    data class Failed(val message: String) : SyncState
}
