package de.schliemannosaurusrex.mukkeklopper.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.library.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ein Eintrag der aktuellen Wiedergabe-Warteschlange (Now Playing > Queue). */
data class QueueItem(
    val index: Int,
    val title: String,
    val artist: String,
    val isCurrent: Boolean,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()

    init {
        connectToService()
        startPositionPolling()
    }

    private fun connectToService() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MukkePlayerService::class.java)
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            syncState()
            syncQueue()
        }, MoreExecutors.directExecutor())
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            _state.update {
                it.copy(
                    title = mediaMetadata.title?.toString(),
                    artist = mediaMetadata.artist?.toString(),
                    albumTitle = mediaMetadata.albumTitle?.toString(),
                    artworkUri = mediaMetadata.artworkUri,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(isLoading = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncQueue()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncQueue()
        }
    }

    private fun syncState() {
        controller?.let { c ->
            _state.update {
                it.copy(
                    isPlaying = c.isPlaying,
                    title = c.mediaMetadata.title?.toString(),
                    artist = c.mediaMetadata.artist?.toString(),
                    albumTitle = c.mediaMetadata.albumTitle?.toString(),
                    artworkUri = c.mediaMetadata.artworkUri,
                    durationMs = c.duration.coerceAtLeast(0L),
                    hasNext = c.hasNextMediaItem(),
                    hasPrevious = c.hasPreviousMediaItem(),
                    shuffleEnabled = c.shuffleModeEnabled,
                    repeatMode = c.repeatMode,
                )
            }
        }
    }

    private fun syncQueue() {
        val c = controller ?: return
        val currentIndex = c.currentMediaItemIndex
        _queue.value = (0 until c.mediaItemCount).map { index ->
            val item = c.getMediaItemAt(index)
            QueueItem(
                index = index,
                title = item.mediaMetadata.title?.toString() ?: "Unknown title",
                artist = item.mediaMetadata.artist?.toString() ?: "Unknown artist",
                isCurrent = index == currentIndex,
            )
        }
    }

    /** Springt direkt zu einem Titel in der Warteschlange (Tap in QueueScreen). */
    fun playQueueIndex(index: Int) {
        AppLog.d(TAG, "playQueueIndex($index)")
        controller?.seekTo(index, 0L)
        controller?.play()
    }

    private fun startPositionPolling() {
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    _state.update {
                        it.copy(
                            currentPositionMs = c.currentPosition.coerceAtLeast(0L),
                            durationMs = c.duration.coerceAtLeast(0L),
                            hasNext = c.hasNextMediaItem(),
                            hasPrevious = c.hasPreviousMediaItem(),
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    fun play() = controller?.play()
    fun pause() = controller?.pause()
    fun seekToNext() = controller?.seekToNextMediaItem()
    fun seekToPrevious() = controller?.seekToPreviousMediaItem()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun toggleRepeat() = controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        AppLog.i(TAG, "playTracks: count=${tracks.size} startIndex=$startIndex")
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(track.albumArtUri)
                        .build()
                )
                .build()
        }
        controller?.apply {
            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlayerViewModel"
    }
}
