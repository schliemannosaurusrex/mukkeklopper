package de.schliemannosaurusrex.kaniamp.library

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ergebnis eines Verschiebe-Versuchs (Phase 8). */
sealed interface MoveResult {
    data class Moved(val count: Int) : MoveResult
    /** Fremde Dateien: der User muss den System-Dialog bestätigen. */
    data class NeedsConfirmation(val request: IntentSender, val tracks: List<Track>) : MoveResult
    data class Collision(val fileNames: List<String>) : MoveResult
    data class Failed(val message: String) : MoveResult
}

class MediaStoreRepository(private val context: Context) {

    private val audioCollection: Uri =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    suspend fun loadTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.OWNER_PACKAGE_NAME,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.ARTIST} ASC, " +
                "${MediaStore.Audio.Media.ALBUM} ASC, " +
                "${MediaStore.Audio.Media.TRACK} ASC"

        context.contentResolver.query(
            audioCollection, projection, selection, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val ownerCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.OWNER_PACKAGE_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                tracks.add(
                    Track(
                        id = id,
                        title = cursor.getString(titleCol) ?: "Unknown Title",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        album = cursor.getString(albumCol) ?: "Unknown Album",
                        albumId = albumId,
                        uri = ContentUris.withAppendedId(audioCollection, id),
                        albumArtUri = Uri.parse("content://media/external/audio/albumart/$albumId"),
                        durationMs = cursor.getLong(durationCol),
                        trackNumber = cursor.getInt(trackCol),
                        relativePath = normalizePath(cursor.getString(pathCol)),
                        displayName = cursor.getString(nameCol) ?: "",
                        isOwnedByApp = cursor.getString(ownerCol) == context.packageName,
                    )
                )
            }
        }
        tracks
    }

    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val albums = mutableListOf<Album>()
        val projection = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
        )

        context.contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Audio.Albums.ALBUM} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST)
            val songsCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                albums.add(
                    Album(
                        id = id,
                        title = cursor.getString(albumCol) ?: "Unknown Album",
                        artist = cursor.getString(artistCol) ?: "Unknown Artist",
                        trackCount = cursor.getInt(songsCol),
                        artUri = Uri.parse("content://media/external/audio/albumart/$id"),
                    )
                )
            }
        }
        albums
    }

    /**
     * Verschiebt Titel nach [targetPath] (rein lokal, kein Rück-Sync). Eigene Dateien
     * gehen per direktem `RELATIVE_PATH`-Update; für fremde verlangt Android eine
     * User-Bestätigung, die der Aufrufer per IntentSender einholen muss.
     * Namenskollisionen brechen ab, es wird nichts überschrieben (PLAN.md Phase 8).
     */
    suspend fun moveTracks(tracks: List<Track>, targetPath: String): MoveResult =
        withContext(Dispatchers.IO) {
            val target = normalizePath(targetPath)
            val moving = tracks.filter { it.relativePath != target }
            if (moving.isEmpty()) return@withContext MoveResult.Moved(0)

            val existingNames = fileNamesIn(target)
            val collisions = moving.filter { it.displayName in existingNames }
            if (collisions.isNotEmpty()) {
                return@withContext MoveResult.Collision(collisions.map { it.displayName })
            }

            val foreign = moving.filterNot { it.isOwnedByApp }
            if (foreign.isNotEmpty()) {
                val request = MediaStore.createWriteRequest(
                    context.contentResolver, foreign.map { it.uri }
                ).intentSender
                return@withContext MoveResult.NeedsConfirmation(request, moving)
            }

            applyMove(moving, target)
        }

    /** Nach erteilter System-Bestätigung: die Titel tatsächlich verschieben. */
    suspend fun applyMove(tracks: List<Track>, targetPath: String): MoveResult =
        withContext(Dispatchers.IO) {
            val target = normalizePath(targetPath)
            var moved = 0
            for (track in tracks) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, target)
                }
                val result = runCatching {
                    context.contentResolver.update(track.uri, values, null, null)
                }
                when {
                    result.isSuccess -> moved += result.getOrDefault(0)
                    result.exceptionOrNull() is RecoverableSecurityException -> Unit
                    else -> return@withContext MoveResult.Failed(
                        result.exceptionOrNull()?.message ?: "Move failed for ${track.displayName}"
                    )
                }
            }
            MoveResult.Moved(moved)
        }

    private fun fileNamesIn(relativePath: String): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            audioCollection,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) cursor.getString(nameCol)?.let(names::add)
        }
        return names
    }

    private fun normalizePath(path: String?): String {
        val trimmed = path?.trim('/') ?: return ""
        return if (trimmed.isEmpty()) "" else "$trimmed/"
    }
}
