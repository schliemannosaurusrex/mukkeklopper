package de.schliemannosaurusrex.mukkeklopper.player

import android.net.Uri

data class PlayerState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val albumTitle: String? = null,
    val artworkUri: Uri? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0,
)
