package de.schliemannosaurusrex.mukkeklopper.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
import kotlinx.coroutines.withContext

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
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    /** Für Android Auto (Browse-Baum) und die "ganzer Ordner"-Wiedergabe beim Tippen im Auto. */
    @Volatile
    private var libraryTracks: List<Track> = emptyList()

    private val audioSessionListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AppLog.i(TAG, "audio session id changed: $audioSessionId")
            EqualizerManager.attach(applicationContext, audioSessionId)
        }
    }

    /**
     * Deckt die Pfade ab, die für "App zeigt Wiedergabe, aber kein Ton" relevant sind:
     * Player-Fehler, Audiofokus-Verlust (via [Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS]),
     * Playback-State-/Volume-/AudioAttributes-Wechsel. Fehler/Fokus-Verlust immer sichtbar (i/w/e),
     * der Rest nur bei aktiviertem Debug-Toggle (d) — siehe [AppLog].
     */
    private val diagnosticsListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "player error: ${error.errorCodeName} (${error.errorCode})", error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            AppLog.d(TAG, "playback state: ${playbackState.stateName()}")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val reasonName = reason.playWhenReadyReasonName()
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                AppLog.w(TAG, "playWhenReady=$playWhenReady reason=$reasonName — Audiofokus verloren/verweigert")
            } else {
                AppLog.d(TAG, "playWhenReady=$playWhenReady reason=$reasonName")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLog.d(TAG, "isPlaying=$isPlaying")
            if (isPlaying) logAudioRoutingSnapshot()
        }

        override fun onAudioAttributesChanged(attributes: AudioAttributes) {
            AppLog.d(TAG, "audio attributes: usage=${attributes.usage} contentType=${attributes.contentType}")
        }

        override fun onVolumeChanged(volume: Float) {
            AppLog.d(TAG, "player volume=$volume")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            AppLog.d(
                TAG,
                "media item transition: '${mediaItem?.mediaMetadata?.title}' reason=${reason.transitionReasonName()}"
            )
        }
    }

    private fun Int.stateName(): String = when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($this)"
    }

    private fun Int.playWhenReadyReasonName(): String = when (this) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "UNKNOWN($this)"
    }

    private fun Int.transitionReasonName(): String = when (this) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "UNKNOWN($this)"
    }

    /**
     * Deckt genau die Lücke ab, die reine Player-Listener nicht zeigen können: "App sagt
     * isPlaying=true, aber wohin geht der Ton wirklich" — z. B. wenn Wireless Android Auto
     * die Steuerung über WLAN, den Audio-Stream aber weiterhin über eine separate
     * Bluetooth-Verbindung führt, die getrennt scheitern kann.
     */
    private fun logAudioRoutingSnapshot() {
        runCatching {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString { "${it.type.audioDeviceTypeName()}(id=${it.id})" }
            AppLog.i(TAG, "audio routing snapshot: isMusicActive=${audioManager.isMusicActive} outputs=[$outputs]")
        }.onFailure { AppLog.w(TAG, "failed to read audio routing snapshot", it) }
    }

    private fun Int.audioDeviceTypeName(): String = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE($this)"
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

    /**
     * Tiefer als [Player.Listener]: reale Renderer-/Sink-Probleme, die trotz sauberem
     * "isPlaying=true" auf Player-Ebene für Stille sorgen könnten (defekter/verzögerter
     * AudioTrack, Codec-Fehler, Underruns).
     */
    private val analyticsListener = object : AnalyticsListener {
        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            AppLog.e(TAG, "audio sink error", audioSinkError)
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            AppLog.e(TAG, "audio codec error", audioCodecError)
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            AppLog.w(TAG, "audio underrun: bufferSizeMs=$bufferSizeMs elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs")
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
        AppLog.i(TAG, "onCreate")
        val player = ExoPlayer.Builder(this).build()
        player.addListener(audioSessionListener)
        player.addListener(persistenceListener)
        player.addListener(diagnosticsListener)
        player.addAnalyticsListener(analyticsListener)
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
            runCatching { withContext(Dispatchers.IO) { MediaStoreRepository(applicationContext).loadTracks() } }
                .onSuccess { libraryTracks = it; AppLog.d(TAG, "Android Auto library cache: ${it.size} tracks") }
                .onFailure { AppLog.w(TAG, "Android Auto: failed to load library", it) }
        }
    }

    private suspend fun ensureLibraryLoaded(): List<Track> {
        if (libraryTracks.isEmpty()) {
            libraryTracks = runCatching { withContext(Dispatchers.IO) { MediaStoreRepository(applicationContext).loadTracks() } }
                .getOrDefault(emptyList())
        }
        return libraryTracks
    }

    private val librarySessionCallback = object : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            AppLog.i(
                TAG,
                "controller connect: package=${controller.packageName} uid=${controller.uid} trusted=${controller.isTrusted}"
            )
            return super.onConnect(session, controller)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            AppLog.d(TAG, "controller post-connect: package=${controller.packageName}")
            super.onPostConnect(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            AppLog.i(TAG, "controller disconnected: package=${controller.packageName}")
            super.onDisconnected(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            AppLog.d(TAG, "onGetLibraryRoot: browser=${browser.packageName}")
            return Futures.immediateFuture(LibraryResult.ofItem(folderMediaItem(ROOT_ID, "MukkeKlopper"), params))
        }

        override fun onGetChildren(
            session: MediaLibraryService.MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            AppLog.d(TAG, "onGetChildren: parentId='$parentId' browser=${browser.packageName}")
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
            AppLog.d(TAG, "onGetItem: mediaId='$mediaId' browser=${browser.packageName}")
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
            AppLog.d(TAG, "onSetMediaItems: ${mediaItems.size} item(s) from ${controller.packageName}, startIndex=$startIndex")
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
                    AppLog.d(TAG, "onSetMediaItems: passthrough, no single track resolved")
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
            AppLog.i(TAG, "onPlaybackResumption requested by ${controller.packageName}")
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val restored = runCatching { restoredQueue() }.getOrNull()
                if (restored != null) {
                    AppLog.i(TAG, "onPlaybackResumption: restored ${restored.mediaItems.size} items, index=${restored.startIndex}")
                    future.set(restored)
                } else {
                    AppLog.w(TAG, "onPlaybackResumption: nothing to resume")
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
            AppLog.d(TAG, "setUpCastPlayer: isCastSessionAvailable=${player.isCastSessionAvailable}")
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        AppLog.d(TAG, "onGetSession: package=${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i(TAG, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy")
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
