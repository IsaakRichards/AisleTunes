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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {

    private var session: MediaSession? = null
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
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()

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

    private fun mediaItem(t: Track?): MediaItem {
        val meta = MediaMetadata.Builder()
            .setStation(Station.NAME)
            .setTitle(t?.title ?: Station.NAME)
            .setArtist(t?.artist?.ifBlank { null } ?: if (t == null) "Live" else Station.NAME)
            .setAlbumTitle(t?.album?.ifBlank { null } ?: Station.NAME)
            .setArtworkUri(t?.artUrl?.let { Uri.parse(it) })
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
