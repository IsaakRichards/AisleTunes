package com.isaakrichards.aisletunes

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var exo: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    override fun onCreate() {
        super.onCreate()

        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.setMediaItem(mediaItem(NowPlaying.current.value))

        exo.addListener(object : Player.Listener {
            // Fallback song titles carried inside the audio stream itself.
            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) entry.title?.let { onSong(it) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) retries = 0
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retries++ < 5) {
                    handler.postDelayed({
                        exo.prepare()
                        exo.play()
                    }, 2_000L * retries)
                }
            }
        })

        val player = LivePlayer(exo)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()

        scope.launch { MetadataStream.songs().collect { onSong(it) } }
    }

    private fun onSong(raw: String) {
        if (!NowPlaying.announce(raw)) return
        applyMetadata()
        scope.launch {
            val found = ArtLookup.find(raw.trim()) ?: return@launch
            NowPlaying.enrich(raw.trim(), found)
            applyMetadata()
        }
    }

    /** Swaps the song info on the live item without interrupting the stream. */
    private fun applyMetadata() {
        val item = mediaItem(NowPlaying.current.value)
        if (exo.mediaItemCount > 0) exo.replaceMediaItem(0, item) else exo.setMediaItem(item)
    }

    private val stationArt: Uri by lazy {
        Uri.parse("android.resource://$packageName/drawable/station_art")
    }

    private fun mediaItem(t: Track?): MediaItem {
        val meta = MediaMetadata.Builder()
            .setStation(Station.NAME)
            .setTitle(t?.title ?: Station.NAME)
            .setArtist(t?.artist?.ifBlank { null } ?: if (t == null) "Live" else Station.NAME)
            .setAlbumTitle(t?.album?.ifBlank { null } ?: Station.NAME)
            .setArtworkUri(t?.artUrl?.let { Uri.parse(it) } ?: stationArt)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
        return MediaItem.Builder()
            .setMediaId("walmart-sams-club-radio")
            .setUri(Station.STREAM)
            .setMediaMetadata(meta)
            .build()
    }

    /** The station as a tile in Android Auto's browse list. */
    private fun stationTile(): MediaItem = MediaItem.Builder()
        .setMediaId(STATION_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(Station.NAME)
                .setSubtitle("Live")
                .setArtworkUri(stationArt)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .build(),
        )
        .build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("AisleTunes")
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = if (parentId == ROOT_ID) ImmutableList.of(stationTile()) else ImmutableList.of()
            return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(stationTile(), null))

        // Cars hand back only the tile's ID; swap in the real stream.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mutableListOf(mediaItem(NowPlaying.current.value)))

        // "Play" from Bluetooth or the car when the app isn't running.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(mediaItem(NowPlaying.current.value)), 0, C.TIME_UNSET,
                ),
            )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val ROOT_ID = "root"
        const val STATION_ID = "walmart-sams-club-radio"
    }

    /** Rejoins the live broadcast after a long pause instead of playing stale audio. */
    private class LivePlayer(player: Player) : ForwardingPlayer(player) {
        private var pausedAt = 0L

        override fun pause() {
            pausedAt = SystemClock.elapsedRealtime()
            super.pause()
        }

        override fun play() {
            val stale = pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 15_000
            if (stale || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                if (stale) super.stop()
                super.prepare()
            }
            pausedAt = 0L
            super.play()
        }
    }
}
