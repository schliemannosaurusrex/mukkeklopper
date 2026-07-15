package de.schliemannosaurusrex.mukkeklopper.cast

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import fi.iki.elonen.NanoHTTPD
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Lokaler HTTP-Server, der einzelne Titel per Track-ID an Cast-Geräte streamt — Chromecast/
 * Google Home können `content://`-URIs des Handys nicht direkt auflösen (PLAN „Debug-Log,
 * Sync-Fix, Queue, Cast" Punkt 4). Läuft nur während einer aktiven Cast-Session
 * ([de.schliemannosaurusrex.mukkeklopper.player.MukkePlayerService] startet/stoppt ihn); ein
 * zufälliges Token pro Start schützt vor unautorisiertem Zugriff im selben LAN.
 */
class LocalMediaServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    val token: String = generateToken()

    /** URL, unter der [trackId] erreichbar ist, sobald der Server läuft und [host] die LAN-IP des Handys ist. */
    fun urlFor(host: String, trackId: Long): String =
        "http://$host:$listeningPort$TRACK_PATH_PREFIX$trackId?$PARAM_TOKEN=$token"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith(TRACK_PATH_PREFIX)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")
        }
        if (session.parms[PARAM_TOKEN] != token) {
            AppLog.w(TAG, "rejected request with invalid/missing token: ${session.uri}")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "Forbidden")
        }
        val trackId = uri.removePrefix(TRACK_PATH_PREFIX).toLongOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Invalid track id")

        return try {
            streamTrack(trackId, session.headers["range"])
        } catch (e: FileNotFoundException) {
            AppLog.w(TAG, "track not found: $trackId", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Track not found")
        } catch (e: Exception) {
            AppLog.e(TAG, "failed to stream track $trackId", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal error")
        }
    }

    private fun streamTrack(trackId: Long, rangeHeader: String?): Response {
        val contentUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), trackId
        )
        val resolver = context.contentResolver
        val mime = resolver.getType(contentUri) ?: "audio/mpeg"
        val length = querySize(contentUri) ?: throw FileNotFoundException(contentUri.toString())

        if (rangeHeader.isNullOrBlank()) {
            val stream = resolver.openInputStream(contentUri) ?: throw FileNotFoundException(contentUri.toString())
            return newFixedLengthResponse(Response.Status.OK, mime, stream, length).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        }

        val (start, end) = parseRange(rangeHeader, length)
        if (start >= length || start < 0) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "")
                .apply { addHeader("Content-Range", "bytes */$length") }
        }
        val stream = resolver.openInputStream(contentUri) ?: throw FileNotFoundException(contentUri.toString())
        stream.skipFully(start)
        val rangeLength = end - start + 1
        return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, rangeLength).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$end/$length")
        }
    }

    private fun querySize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun parseRange(header: String, totalLength: Long): Pair<Long, Long> {
        val spec = header.removePrefix("bytes=")
        val parts = spec.split("-", limit = 2)
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        return start to end
    }

    /** [InputStream.skip] darf weniger als angefordert überspringen — bis zum Ziel wiederholen. */
    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private companion object {
        const val TAG = "LocalMediaServer"
        const val DEFAULT_PORT = 8482
        const val TRACK_PATH_PREFIX = "/track/"
        const val PARAM_TOKEN = "token"

        fun generateToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
