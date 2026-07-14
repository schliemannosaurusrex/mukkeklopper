package de.schliemannosaurusrex.kaniamp.library

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val uri: Uri,
    val albumArtUri: Uri?,
    val durationMs: Long,
    val trackNumber: Int,
    /** MediaStore `RELATIVE_PATH`, immer mit Schrägstrich am Ende, z. B. `Music/KaniAmp/Album/`. */
    val relativePath: String = "",
    val displayName: String = "",
    /** true, wenn KaniAmp die Datei angelegt hat — nur dann ist Verschieben ohne System-Dialog möglich. */
    val isOwnedByApp: Boolean = false,
)
