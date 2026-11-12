package com.isaakrichards.aisletunes

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ring: AnimatorSet? = null

    private lateinit var playButton: ImageButton
    private lateinit var status: TextView
    private lateinit var liveDot: View
    private lateinit var eyebrow: TextView
    private lateinit var cover: ImageView
    private lateinit var coverPlaceholder: TextView
    private lateinit var wash: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var ringView: View
    private var shownArt: String? = "unset"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        playButton = findViewById(R.id.play)
        status = findViewById(R.id.status)
        liveDot = findViewById(R.id.live_dot)
        eyebrow = findViewById(R.id.eyebrow)
        cover = findViewById(R.id.cover)
        coverPlaceholder = findViewById(R.id.cover_placeholder)
        wash = findViewById(R.id.wash)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        album = findViewById(R.id.album)
        historyList = findViewById(R.id.history)
        ringView = findViewById(R.id.ring)

        val content = findViewById<View>(R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val pad = (20 * resources.displayMetrics.density).toInt()
            v.updatePadding(left = bars.left + pad, right = bars.right + pad, top = bars.top + pad, bottom = bars.bottom + pad)
            insets
        }

        // Square cover sized to the screen, capped at 320dp.
        val dm = resources.displayMetrics
        val size = minOf((dm.widthPixels * 0.78f).toInt(), (320 * dm.density).toInt())
        findViewById<View>(R.id.cover_frame).layoutParams.apply { width = size; height = size }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wash.setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
        }

        playButton.setOnClickListener { togglePlay() }
        playButton.isEnabled = false
        setState(Player.STATE_IDLE, false)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NowPlaying.current.collect { showTrack(it) } }
                launch { NowPlaying.history.collect { showHistory(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (_: Exception) { null } ?: return@addListener
            controller = c
            playButton.isEnabled = true
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    setState(player.playbackState, player.playWhenReady, player.playerError)
                }
            })
            setState(c.playbackState, c.playWhenReady, c.playerError)
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_IDLE) {
            c.stop()
        } else {
            c.prepare()
            c.play()
        }
    }

    private fun setState(state: Int, wantsPlay: Boolean, error: PlaybackException? = null) {
        val active = wantsPlay && state != Player.STATE_IDLE && state != Player.STATE_ENDED
        val live = active && state == Player.STATE_READY
        playButton.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        playButton.contentDescription = getString(if (active) R.string.stop else R.string.play)
        status.text = when {
            error != null && !active -> getString(R.string.status_error)
            live -> getString(R.string.status_live)
            active && state == Player.STATE_BUFFERING -> getString(R.string.status_buffering)
            else -> getString(R.string.status_idle)
        }
        liveDot.isActivated = live
        eyebrow.isActivated = live
        animateRing(live)
    }

    private fun animateRing(on: Boolean) {
        if (on && ring == null) {
            val sx = ObjectAnimator.ofFloat(ringView, View.SCALE_X, 0.92f, 1.22f)
            val sy = ObjectAnimator.ofFloat(ringView, View.SCALE_Y, 0.92f, 1.22f)
            val a = ObjectAnimator.ofFloat(ringView, View.ALPHA, 0.6f, 0f)
            listOf(sx, sy, a).forEach { it.repeatCount = ValueAnimator.INFINITE; it.duration = 1800 }
            ring = AnimatorSet().apply { playTogether(sx, sy, a); start() }
            ringView.visibility = View.VISIBLE
        } else if (!on && ring != null) {
            ring?.cancel()
            ring = null
            ringView.visibility = View.INVISIBLE
        }
    }

    private fun showTrack(t: Track?) {
        if (t == null) {
            title.setText(R.string.waiting_title)
            artist.setText(R.string.waiting_artist)
            album.text = ""
        } else {
            title.text = t.title.ifBlank { t.raw }
            artist.text = t.artist.ifBlank { Station.NAME }
            album.text = listOf(t.album, t.year).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val art = t?.artUrl
        if (art == shownArt) return
        shownArt = art
        if (art == null) {
            cover.setImageDrawable(null)
            cover.visibility = View.INVISIBLE
            coverPlaceholder.visibility = View.VISIBLE
            wash.setImageDrawable(null)
        } else {
            cover.load(art) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        cover.visibility = View.VISIBLE
                        coverPlaceholder.visibility = View.INVISIBLE
                    },
                    onError = { _, _ ->
                        cover.visibility = View.INVISIBLE
                        coverPlaceholder.visibility = View.VISIBLE
                    },
                )
            }
            wash.load(art) { crossfade(800) }
        }
    }

    private fun showHistory(items: List<Track>) {
        historyList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (items.isEmpty()) {
            val empty = inflater.inflate(R.layout.item_history_empty, historyList, false)
            historyList.addView(empty)
            return
        }
        val fmt = DateFormat.getTimeFormat(this)
        for (t in items) {
            val row = inflater.inflate(R.layout.item_history, historyList, false)
            row.findViewById<TextView>(R.id.h_title).text = t.title.ifBlank { t.raw }
            row.findViewById<TextView>(R.id.h_artist).text = t.artist.ifBlank { "—" }
            row.findViewById<TextView>(R.id.h_time).text = fmt.format(Date(t.airedAt))
            val thumb = row.findViewById<ImageView>(R.id.h_art)
            t.thumbUrl?.let { thumb.load(it) { crossfade(true) } }
            historyList.addView(row)
        }
    }
}
