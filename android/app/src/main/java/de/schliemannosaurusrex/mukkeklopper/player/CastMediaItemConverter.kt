package de.schliemannosaurusrex.mukkeklopper.player

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import de.schliemannosaurusrex.mukkeklopper.cast.LocalMediaServer
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata

/**
 * Ersetzt die `content://`-URI eines [MediaItem] durch eine `http://<LAN-IP>:<port>/track/<id>`-
 * URL, die [LocalMediaServer] bedient — Chromecast/Google Home können lokale content-URIs nicht
 * auflösen (PLAN „Debug-Log, Sync-Fix, Queue, Cast" Punkt 4). Album-Art wird in v1 ausgelassen
 * (bekannte Einschränkung — müsste sonst ebenfalls über den lokalen Server ausgeliefert werden).
 */
class CastMediaItemConverter(
    private val context: Context,
    private val server: LocalMediaServer,
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val trackId = mediaItem.mediaId.toLongOrNull()
        val host = localIpAddress()
        val url = if (trackId != null && host != null) {
            server.urlFor(host, trackId)
        } else {
            AppLog.w(TAG, "cannot build cast URL for mediaId=${mediaItem.mediaId} (no LAN IP found)")
            mediaItem.localConfiguration?.uri?.toString().orEmpty()
        }
        val mimeType = mediaItem.localConfiguration?.uri
            ?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
            ?: "audio/mpeg"

        val metadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
        mediaItem.mediaMetadata.title?.let { metadata.putString(CastMediaMetadata.KEY_TITLE, it.toString()) }
        mediaItem.mediaMetadata.artist?.let { metadata.putString(CastMediaMetadata.KEY_ARTIST, it.toString()) }
        mediaItem.mediaMetadata.albumTitle?.let { metadata.putString(CastMediaMetadata.KEY_ALBUM_TITLE, it.toString()) }

        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .build()
        return MediaQueueItem.Builder(mediaInfo).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val contentId = mediaQueueItem.media?.contentId.orEmpty()
        val title = mediaQueueItem.media?.metadata?.getString(CastMediaMetadata.KEY_TITLE)
        return MediaItem.Builder()
            .setMediaId(contentId)
            .setUri(contentId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            )
            .build()
    }

    private fun localIpAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val link = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return link.linkAddresses
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }

    private companion object {
        const val TAG = "CastMediaItemConverter"
    }
}
