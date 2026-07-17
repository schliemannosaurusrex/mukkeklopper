package de.schliemannosaurusrex.mukkeklopper.player

import kotlinx.serialization.Serializable

/**
 * Persistierter Wiedergabe-Zustand (Queue + Position), damit der zuletzt gespielte
 * Titel App-/Service-Neustarts überlebt. Laufzeit-State — bewusst nicht Teil des
 * Config-Exports (wie `last_sync_report`).
 */
@Serializable
data class PlaybackState(
    /** MediaStore-IDs der Queue in Wiedergabereihenfolge. */
    val trackIds: List<Long> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
)
