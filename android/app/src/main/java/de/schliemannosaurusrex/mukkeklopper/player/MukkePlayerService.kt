package de.schliemannosaurusrex.mukkeklopper.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.framework.CastContext
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.schliemannosaurusrex.mukkeklopper.MainActivity
import de.schliemannosaurusrex.mukkeklopper.cast.LocalMediaServer
import de.schliemannosaurusrex.mukkeklopper.debug.AppLog
import de.schliemannosaurusrex.mukkeklopper.library.FolderTree
import de.schliemannosaurusrex.mukkeklopper.library.MediaStoreRepository
import de.schliemannosaurusrex.mukkeklopper.library.Track
import de.schliemannosaurusrex.mukkeklopper.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * [MediaLibraryService] statt eines einfachen `MediaSessionService`, damit Android Auto einen
 * browsierbaren Medienbaum bekommt (PLAN „Debug-Log, Sync-Fix, Queue, Cast" Punkt 5) — der
 * Ordner-Baum wird 1:1 aus [FolderTree] übernommen, exakt wie in der App-UI. Hält daneben einen
 * [CastPlayer]; bei Cast-Verbindung wird der aktive Player der [MediaSession] getauscht und
 * [LocalMediaServer] gestartet/gestoppt (Punkt 4).
 */
class MukkePlayerService : MediaLibraryService() {

    private var mediaSession: MediaLibraryService.MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private var castPlayer: CastPlayer? = null
    private var localMediaServer: LocalMediaServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }

    /** Für Android Auto (Browse-Baum) und die "ganzer Ordner"-Wiedergabe beim Tippen im Auto. */
    @Volatile
    private var libraryTracks: List<Track> = emptyList()

    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            EqualizerManager.attach(applicationContext, audioSessionId)
        }
    }

    /**
     * Persistiert die Queue bei Titelwechsel und Pause (bewusst nicht pro
     * Fortschritts-Tick), damit der zuletzt gespielte Titel Neustarts überlebt.
     */
    private val persistenceListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            savePlaybackStateAsync()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) savePlaybackStateAsync()
        }
    }

    private fun currentPlaybackState(): PlaybackState? {
        val player = mediaSession?.player ?: exoPlayer ?: return null
        if (player.mediaItemCount == 0) return null
        val trackIds = (0 until player.mediaItemCount)
            .mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (trackIds.isEmpty()) return null
        return PlaybackState(
            trackIds = trackIds,
            currentIndex = player.currentMediaItemIndex.coerceIn(0, trackIds.size - 1),
            positionMs = player.currentPosition.coerceAtLeast(0),
        )
    }

    private fun savePlaybackStateAsync() {
        val state = currentPlaybackState() ?: return
        serviceScope.launch {
            runCatching { settingsRepository.setPlaybackState(state) }
        }
    }

    /** Löst den persistierten Zustand gegen die aktuelle Library auf (gelöschte Tracks entfallen). */
    private suspend fun restoredQueue(): MediaSession.MediaItemsWithStartPosition? {
        val state = settingsRepository.playbackState.first() ?: return null
        if (state.trackIds.isEmpty()) return null
        val tracksById = ensureLibraryLoaded().associateBy { it.id }
        val currentTrackId = state.trackIds.getOrNull(state.currentIndex)
        val items = state.trackIds.mapNotNull { tracksById[it] }.map(::trackMediaItem)
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.mediaId == currentTrackId?.toString() }.coerceAtLeast(0)
        val position = if (index == 0 && items[0].mediaId != currentTrackId?.toString()) 0L else state.positionMs
        return MediaSession.MediaItemsWithStartPosition(items, index, position)
    }

    private val castSessionListener = object : SessionAvailabilityListener {
        override fun onCastSessionAvailable() {
            AppLog.i(TAG, "cast session available — switching playback to CastPlayer")
            startLocalMediaServer()
            castPlayer?.let { cast ->
                exoPlayer?.let { exo -> transferPlayback(from = exo, to = cast) }
                mediaSession?.player = cast
            }
        }

        override fun onCastSessionUnavailable() {
            AppLog.i(TAG, "cast session ended — switching playback back to local ExoPlayer")
            exoPlayer?.let { exo ->
                castPlayer?.let { cast -> transferPlayback(from = cast, to = exo) }
                mediaSession?.player = exo
            }
            stopLocalMediaServer()
        }
    }

    /**
     * Überträgt Queue, Position und Play-Status beim Wechsel ExoPlayer ↔ CastPlayer.
     * Ohne diesen Transfer startet der Ziel-Player leer — das Cast-Gerät zeigte dann
     * "kein Titel ausgewählt", obwohl lokal etwas lief.
     */
    private fun transferPlayback(from: Player, to: Player) {
        val items = (0 until from.mediaItemCount).map { from.getMediaItemAt(it) }
        if (items.isEmpty()) return
        val index = from.currentMediaItemIndex
        val position = from.currentPosition
        val playWhenReady = from.playWhenReady
        from.pause()
        to.setMediaItems(items, index, position)
        to.prepare()
        to.playWhenReady = playWhenReady
        AppLog.d(TAG, "transferred ${items.size} items (index=$index pos=${position}ms play=$playWhenReady)")
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(audioSessionListener)
        player.addListener(persistenceListener)
        exoPlayer = player
        // Der Equalizer (Phase 9) braucht eine gültige Audio-Session; ExoPlayer legt sie
        // schon beim Start an, der Listener greift erst bei einem späteren Wechsel.
        EqualizerManager.attach(applicationContext, player.audioSessionId)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaLibraryService.MediaLibrarySession
            .Builder(this, player, librarySessionCallback)
            .setSessionActivity(sessionActivity)
            .build()

        refreshLibraryCache()
        setUpCastPlayer()

        // Zuletzt gespielte Queue still vorbereiten (kein Auto-Play), damit die UI
        // nach App-Neustart direkt den letzten Titel zeigt. Ein Controller, der
        // schon Items gesetzt hat, wird nicht überschrieben.
        serviceScope.launch {
            val restored = runCatching { restoredQueue() }.getOrNull() ?: return@launch
            val target = mediaSession?.player ?: return@launch
            if (target.mediaItemCount > 0) return@launch
            target.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
            target.prepare()
            AppLog.d(TAG, "restored ${restored.mediaItems.size} queue items (index=${restored.startIndex})")
        }
    }

    private fun refreshLibraryCache() {
        serviceScope.launch {
            runCatching { MediaStoreRepository(applicationContext).loadTracks() }
                .onSuccess { libraryTracks = it; AppLog.d(TAG, "Android Auto library cache: ${it.size} tracks") }
                .onFailure { AppLog.w(TAG, "Android Auto: failed to load library", it) }
        }
    }

    private suspend fun ensureLibraryLoaded(): List<Track> {
        if (libraryTracks.isEmpty()) {
            libraryTracks = runCatching { MediaStoreRepository(applicationContext).loadTracks() }
                .getOrDefault(emptyList())
        }
        return libraryTracks
    }

    private val librarySessionCallback = object : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderMediaItem(ROOT_ID, "MukkeKlopper"), params))

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val tracks = ensureLibraryLoaded()
                // Der Einstieg (ROOT_ID) zeigt das in der App markierte Startverzeichnis
                // (library_root, Stern in der Library) statt der MediaStore-Wurzel —
                // Unterordner navigieren unverändert über echte Pfade.
                val path = if (parentId == ROOT_ID) {
                    settingsRepository.settings.first().libraryRoot
                } else {
                    parentId
                }
                val content = FolderTree.contentOf(tracks, path)
                val items = content.subFolders.map { folderMediaItem(it.path, it.name) } +
                    content.tracks.map { trackMediaItem(it) }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                val tracks = ensureLibraryLoaded()
                val item = mediaId.toLongOrNull()?.let { id -> tracks.find { it.id == id } }?.let(::trackMediaItem)
                    ?: run {
                        val path = if (mediaId == ROOT_ID) "" else mediaId
                        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "MukkeKlopper" }
                        folderMediaItem(mediaId, name)
                    }
                future.set(LibraryResult.ofItem(item, null))
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val tracks = ensureLibraryLoaded()
                val requestedTrack = mediaItems.singleOrNull()
                    ?.mediaId?.toLongOrNull()
                    ?.let { id -> tracks.find { it.id == id } }
                if (requestedTrack != null) {
                    // Wie in LibraryScreen: Tippen auf einen Titel spielt den ganzen Ordner
                    // (ohne Unterordner) als Queue — identische Regel für Android Auto.
                    val folderTracks = FolderTree.contentOf(tracks, requestedTrack.relativePath).tracks
                    val resolvedIndex = folderTracks.indexOfFirst { it.id == requestedTrack.id }.coerceAtLeast(0)
                    AppLog.d(TAG, "onSetMediaItems: expanding '${requestedTrack.relativePath}' to ${folderTracks.size} tracks")
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            folderTracks.map(::trackMediaItem), resolvedIndex, startPositionMs,
                        )
                    )
                } else {
                    val resolved = mediaItems.map { item ->
                        item.mediaId.toLongOrNull()?.let { id -> tracks.find { it.id == id } }
                            ?.let(::trackMediaItem) ?: item
                    }
                    future.set(MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
                }
            }
            return future
        }

        /** System-/Media-Button-Resumption: zuletzt gespielte Queue wiederherstellen. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val restored = runCatching { restoredQueue() }.getOrNull()
                if (restored != null) {
                    future.set(restored)
                } else {
                    future.setException(IllegalStateException("No playback state to resume"))
                }
            }
            return future
        }
    }

    private fun folderMediaItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun trackMediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.albumArtUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun setUpCastPlayer() {
        runCatching {
            val server = LocalMediaServer(applicationContext)
            localMediaServer = server
            val castContext = CastContext.getSharedInstance(this)
            val player = CastPlayer(castContext, CastMediaItemConverter(this, server))
            player.setSessionAvailabilityListener(castSessionListener)
            castPlayer = player
            if (player.isCastSessionAvailable) {
                startLocalMediaServer()
                exoPlayer?.let { exo -> transferPlayback(from = exo, to = player) }
                mediaSession?.player = player
            }
        }.onFailure {
            // Kein Google Play services / kein Cast-fähiges Gerät — lokale Wiedergabe bleibt
            // unberührt, Cast-Button wird in der UI dann einfach nie aktiv.
            AppLog.w(TAG, "Cast unavailable on this device", it)
        }
    }

    private fun startLocalMediaServer() {
        val server = localMediaServer ?: return
        if (server.isAlive) return
        runCatching {
            server.start()
            AppLog.i(TAG, "LocalMediaServer started on port ${server.listeningPort}")
        }.onFailure {
            // Gleiche Fehlerklasse wie beim Sync-Bug: ohne ACCESS_LOCAL_NETWORK (Android 17+)
            // verwirft das OS eingehende LAN-Requests, ohne dass start() selbst fehlschlägt.
            AppLog.e(TAG, "Failed to start LocalMediaServer — check Local Network permission", it)
        }
    }

    private fun stopLocalMediaServer() {
        val server = localMediaServer ?: return
        if (!server.isAlive) return
        runCatching { server.stop() }
        AppLog.i(TAG, "LocalMediaServer stopped")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? =
        mediaSession

    override fun onDestroy() {
        // Letzten Stand sichern, bevor Scope und Player freigegeben werden — die
        // Listener-basierten Speicherpunkte decken einen Service-Kill nicht ab.
        currentPlaybackState()?.let { state ->
            runCatching { runBlocking { settingsRepository.setPlaybackState(state) } }
        }
        serviceScope.cancel()
        EqualizerManager.release()
        stopLocalMediaServer()
        castPlayer?.setSessionAvailabilityListener(null)
        // MediaSession.release() gibt nur die Session frei, nicht den Player — beide Player
        // (die evtl. nie aktiv waren) werden hier explizit und unabhängig voneinander freigegeben.
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        castPlayer?.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MukkePlayerService"
        const val ROOT_ID = "root"
    }
}
