package de.schliemannosaurusrex.kaniamp.library

import android.net.Uri

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val trackCount: Int,
    val artUri: Uri?,
)
