package com.tvplayer.app.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.R
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.Http
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.PlayerQueue
import com.tvplayer.app.data.ProfileStore
import androidx.lifecycle.lifecycleScope
import com.tvplayer.app.databinding.ActivityPlayerBinding
import java.util.Locale

/**
 * Full-screen ExoPlayer that tries hard to play whatever the server sends, plus:
 *  - settings: quality, subtitles, audio track, screen size, subtitle size, speed
 *  - side list of episodes / channels to jump anywhere while watching
 *  - saves progress for "continue watching" and resumes where you stopped
 *
 * Remote: ▲▼ / CH± change channel (live) • OK on live opens the channel list
 *         MENU opens settings • BACK closes panels, then exits.
 */
/** One "screen size" choice. [ratio] > 0 forces that aspect ratio. */
class AspectMode(val name: String, val resize: Int, val ratio: Float = 0f)

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private companion object {
        const val MAX_RETRIES = 4
        const val SAVE_EVERY_MS = 10_000L
        /** Up-next card appears this long before an episode ends. */
        const val UP_NEXT_MS = 15_000L
        val PROGRESSIVE_EXT = listOf(".ts", ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".mp3", ".aac", ".flv")
        /** Screen size options: how the video fills the screen, optionally forcing an aspect ratio. */
        val ASPECTS = arrayOf(
            AspectMode("تلقائي (الصورة كاملة بدون قص)", AspectRatioFrameLayout.RESIZE_MODE_FIT),
            AspectMode("ملء الشاشة (مع قص الأطراف)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
            AspectMode("تمديد لملء الشاشة", AspectRatioFrameLayout.RESIZE_MODE_FILL),
            AspectMode("16:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 16f / 9f),
            AspectMode("4:3", AspectRatioFrameLayout.RESIZE_MODE_FIT, 4f / 3f),
            AspectMode("21:9 (سينما)", AspectRatioFrameLayout.RESIZE_MODE_FIT, 21f / 9f),
            AspectMode("18:9", AspectRatioFrameLayout.RESIZE_MODE_FIT, 2f),
        )
        val ORIENTATIONS = arrayOf(
            "أفقي (يلف مع الجهاز)" to android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            "حسب تدوير الجهاز (أفقي وعمودي)" to android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            "عمودي" to android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        )
        val QUALITY_CAPS = intArrayOf(0, 2160, 1080, 720, 480, 360)
        val QUALITY_NAMES = arrayOf("تلقائي (أفضل جودة متاحة)", "4K كحد أقصى", "Full HD 1080p كحد أقصى", "HD 720p كحد أقصى", "480p كحد أقصى (توفير نت)", "360p كحد أقصى")
        val SUB_SCALES = floatArrayOf(0.75f, 1f, 1.3f, 1.6f, 2f)
        val SUB_NAMES = arrayOf("صغير", "عادي", "كبير", "كبير جداً", "ضخم")
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var store: ProfileStore
    private lateinit var listAdapter: ChannelAdapter
    private lateinit var stripAdapter: ChannelAdapter

    /** Guide for live channels (Xtream servers only). */
    private val epgApi by lazy {
        profileId?.let { ProfileStore(this).get(it) }?.takeIf { it.type == com.tvplayer.app.data.ServerType.XTREAM }?.let { com.tvplayer.app.data.XtreamApi(it) }
    }

    private fun showsEpisodes() = items.size > 1 && items.getOrNull(index)?.kind == ItemKind.EPISODE

    /** Episodes strip is open (only after the viewer asked for it). */
    private var stripOpen = false

    /** Series: toggle the episodes strip over the controls. Channels: the side list. */
    private fun openEpisodesOrList() {
        if (!showsEpisodes()) { openList(); return }
        if (stripOpen) closeStrip() else openStrip()
    }

    /** The strip is its own panel: the controls hide while it is open so nothing overlaps. */
    private fun openStrip() {
        closePanel()
        stripOpen = true
        b.playerView.hideController()
        b.episodesBar.visibility = View.VISIBLE
        b.episodesStrip.scrollToPosition(index)
        b.episodesStrip.post {
            b.episodesStrip.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                ?: b.episodesStrip.requestFocus()
        }
    }

    private fun closeStrip(showControls: Boolean = false) {
        if (!stripOpen && !b.episodesBar.isVisible) return
        stripOpen = false
        b.episodesBar.visibility = View.GONE
        if (showControls) b.playerView.showController()
    }

    /** Views inside the custom controller (player_controls.xml). */
    private class Ctl(container: View) {
        val root: View = container.findViewById(androidx.media3.ui.R.id.exo_controls_background)
        val title: android.widget.TextView = container.findViewById(R.id.ctlTitle)
        val subtitle: android.widget.TextView = container.findViewById(R.id.ctlSubtitle)
        val back: View = container.findViewById(R.id.btnBack)
        val cast: androidx.mediarouter.app.MediaRouteButton = container.findViewById(R.id.btnCast)
        val settings: View = container.findViewById(R.id.btnSettings)
        val list: android.widget.Button = container.findViewById(R.id.btnList)
        val subs: android.widget.Button = container.findViewById(R.id.btnSubs)
        val aspect: android.widget.Button = container.findViewById(R.id.btnAspect)
        val nextEp: android.widget.Button = container.findViewById(R.id.btnNextEp)
        val live: View = container.findViewById(R.id.ctlLive)
        val rewWrap: View = container.findViewById(R.id.rewWrap)
        val ffwdWrap: View = container.findViewById(R.id.ffwdWrap)
        val position: View = container.findViewById(androidx.media3.ui.R.id.exo_position)
        val duration: View = container.findViewById(androidx.media3.ui.R.id.exo_duration)
        val progress: View = container.findViewById(androidx.media3.ui.R.id.exo_progress)
    }
    private lateinit var ctl: Ctl

    /** Next episode in the queue, or -1. */
    private fun nextEpisodeIndex(): Int {
        val cur = items.getOrNull(index) ?: return -1
        val next = items.getOrNull(index + 1) ?: return -1
        return if (cur.kind == ItemKind.EPISODE && next.kind == ItemKind.EPISODE) index + 1 else -1
    }

    /** Fits the controls to what is playing: live vs. video, series vs. movie. */
    private fun updateControls() {
        val live = isLive()
        ctl.rewWrap.isVisible = !live
        ctl.ffwdWrap.isVisible = !live
        ctl.position.isVisible = !live
        ctl.duration.isVisible = !live
        ctl.progress.visibility = if (live) View.INVISIBLE else View.VISIBLE
        ctl.live.isVisible = live
        b.playerView.setShowPreviousButton(items.size > 1)
        b.playerView.setShowNextButton(items.size > 1)
        ctl.list.isVisible = items.size > 1
        val isLiveList = items.first().kind == ItemKind.LIVE
        ctl.list.text = if (isLiveList) "القنوات" else "الحلقات"
        ctl.list.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isLiveList) R.drawable.ic_pl_list else R.drawable.ic_pl_episodes, 0, 0, 0,
        )
        ctl.nextEp.isVisible = nextEpisodeIndex() >= 0
    }

    /** Title / second line: series + episode, or channel + what's on now. */
    private fun updateTitles() {
        val item = items.getOrNull(index) ?: return
        when {
            item.kind == ItemKind.EPISODE && item.seriesName != null -> {
                ctl.title.text = item.seriesName
                ctl.subtitle.text = item.name
                ctl.subtitle.visibility = View.VISIBLE
            }
            item.kind == ItemKind.LIVE -> {
                ctl.title.text = if (items.size > 1) "${index + 1}   ${item.name}" else item.name
                val pid = profileId
                val now = if (pid != null) com.tvplayer.app.data.Epg.now(pid, item.id) else null
                ctl.subtitle.text = now?.let { "الآن: ${it.title}  (${it.timeRange()})" }.orEmpty()
                ctl.subtitle.visibility = if (now != null) View.VISIBLE else View.GONE
            }
            else -> {
                ctl.title.text = item.name
                ctl.subtitle.visibility = View.GONE
            }
        }
    }

    private fun loadEpg(item: Channel) {
        val api = epgApi ?: return
        val pid = profileId ?: return
        if (item.kind != ItemKind.LIVE) return
        com.tvplayer.app.data.Epg.request(lifecycleScope, api, pid, item.id) {
            if (items.getOrNull(index)?.url == item.url) {
                updateTitles()
                com.tvplayer.app.data.Epg.now(pid, item.id)?.let { showInfo("${index + 1}   ${item.name}\nالآن: ${it.title}") }
            }
        }
    }

    // ---------- typing a channel number on the remote ----------

    private var numberBuffer = ""
    private val numberRunnable = Runnable {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n != null && n in 1..items.size) startItem(n - 1, PlayerQueue.RESUME)
        else if (n != null) showInfo("ما في قناة رقم $n")
    }
    private var exo: ExoPlayer? = null

    // ---------- Chromecast ----------
    private var castPlayer: androidx.media3.cast.CastPlayer? = null
    /** True while the video plays on a Chromecast instead of this device. */
    private var casting = false
    private val handler = Handler(Looper.getMainLooper())

    private var items: List<Channel> = emptyList()
    private var profileId: String? = null
    private var index = 0
    private var pendingIndex = -1
    private var attempts: List<Pair<String, String?>> = emptyList()
    private var attempt = 0
    private var retries = 0
    private var playToken = 0
    /** Where to start the current item: explicit ms, or PlayerQueue.RESUME. */
    private var resumePosition = PlayerQueue.RESUME

    private val extractors by lazy {
        DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
    }

    private val hideInfo = Runnable { b.info.visibility = View.GONE }
    private val zapRunnable = Runnable {
        val i = pendingIndex
        pendingIndex = -1
        if (i >= 0) startItem(i, PlayerQueue.RESUME)
    }
    private val saveRunnable = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Arabic interface: right-to-left, whatever the phone's language.
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        ctl = Ctl(b.playerView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        store = ProfileStore(this)

        items = PlayerQueue.items
        if (items.isEmpty()) { finish(); return }
        profileId = PlayerQueue.profileId
        index = PlayerQueue.index.coerceIn(0, items.size - 1)
        resumePosition = PlayerQueue.startPosition
        savedInstanceState?.let {
            index = it.getInt("index", index).coerceIn(0, items.size - 1)
            resumePosition = it.getLong("pos", PlayerQueue.RESUME)
        }

        b.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        b.playerView.controllerShowTimeoutMs = 4000
        b.playerView.setShowRewindButton(true)
        b.playerView.setShowFastForwardButton(true)
        b.playerView.setShowSubtitleButton(false)
        // Show/hide the whole overlay at once (our own quick fade below), not Media3's staged animation.
        b.playerView.setControllerAnimationEnabled(false)
        if (!Device.isTv(this)) {
            // Touch gestures: volume, brightness, seeking, double-tap ±10s.
            b.playerView.setOnTouchListener(
                PlayerGestures(
                    activity = this,
                    player = { exo },
                    isLive = { isLive() },
                    osd = { showInfo(it) },
                    level = { volume, pct -> showLevel(volume, pct) },
                    toggleControls = {
                        if (b.listPanel.isVisible) closeList()
                        else if (panelOpen) closePanel()
                        else if (stripOpen) closeStrip()
                        else if (b.playerView.isControllerFullyVisible) b.playerView.hideController()
                        else b.playerView.showController()
                    },
                )
            )
        }
        applyAspect()
        if (!Device.isTv(this)) requestedOrientation = ORIENTATIONS[store.orientation.coerceIn(0, ORIENTATIONS.size - 1)].second
        ctl.aspect.setOnClickListener { aspectPage() }
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                if (v == View.VISIBLE) {
                    ctl.root.alpha = 0f
                    ctl.root.animate().alpha(1f).setDuration(160).start()
                }
                if (!b.listPanel.isVisible) {
                    if (v == View.VISIBLE) updateControls()
                    // Controls and the episodes strip never share the screen.
                    if (v == View.VISIBLE && stripOpen) closeStrip()
                }
            }
        )
        ctl.subs.setOnClickListener { subtitlePage() }
        ctl.nextEp.setOnClickListener { playNextEpisode() }
        b.nextPlay.setOnClickListener { playNextEpisode() }
        b.nextCancel.setOnClickListener {
            nextCancelledFor = index
            b.nextCard.visibility = View.GONE
        }
        b.panelScrim.setOnClickListener { closePanel() }
        b.panelClose.setOnClickListener { closePanel() }
        b.panelBack.setOnClickListener { panelBackAction?.invoke() ?: closePanel() }
        applySubtitleStyle()
        setupCast()

        ctl.back.setOnClickListener { finish() }
        ctl.settings.setOnClickListener { showSettings() }
        ctl.list.setOnClickListener { openEpisodesOrList() }
        updateControls()
        b.btnCloseEpisodes.setOnClickListener { closeStrip(showControls = true) }

        listAdapter = ChannelAdapter(
            onClick = { _, pos -> closeList(); if (pos != index) startItem(pos, PlayerQueue.RESUME) },
            onLongClick = {},
            progressOf = { c -> profileId?.let { store.progressFor(it, c)?.percent } },
        )
        b.listItems.layoutManager = LinearLayoutManager(this)
        b.listItems.adapter = listAdapter

        // Series: the episodes strip shown with the controls.
        stripAdapter = ChannelAdapter(
            onClick = { _, pos ->
                closeStrip()
                if (pos != index) startItem(pos, PlayerQueue.RESUME)
            },
            onLongClick = {},
            progressOf = { c -> profileId?.let { store.progressFor(it, c)?.percent } },
            subtitleOf = { c -> c.duration.ifBlank { null } },
        ).apply {
            grid = true
            landscape = true
            fixedWidthPx = ((if (Device.isTv(this@PlayerActivity)) 230 else 150) * resources.displayMetrics.density).toInt()
            seriesTitles = false // each card shows its own episode name
        }
        b.episodesStrip.layoutManager = LinearLayoutManager(this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)
        b.episodesStrip.adapter = stripAdapter
        if (items.first().kind == ItemKind.EPISODE) stripAdapter.submit(items)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panelOpen -> panelBackAction?.invoke() ?: closePanel()
                    b.listPanel.isVisible -> closeList()
                    stripOpen -> closeStrip()
                    b.playerView.isControllerFullyVisible -> b.playerView.hideController()
                    else -> finish()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putLong("pos", resumePosition)
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- player lifecycle ----------

    private fun initPlayer() {
        if (exo != null) return
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 4_000)
            .build()
        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // Arabic subtitles / audio picked automatically when the stream has them.
        player.trackSelectionParameters = withQualityCap(
            player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage("ar")
                .setPreferredAudioLanguage("ar"),
            store.maxQuality,
        ).build()
        player.addListener(listener)
        player.playWhenReady = true
        exo = player
        if (casting && castPlayer != null) {
            // Came back to the app while casting: keep controlling the TV.
            b.playerView.player = castPlayer
            b.castOverlay.visibility = View.VISIBLE
            updateTitles()
        } else {
            b.playerView.player = if (items.size > 1) zappingPlayer(player) else player
            startItem(index, resumePosition)
        }
        handler.postDelayed(saveRunnable, SAVE_EVERY_MS)
        handler.post(upNextTick)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        exo?.let {
            saveProgress()
            resumePosition = if (isLive()) PlayerQueue.RESUME else it.currentPosition
            it.removeListener(listener)
            it.release()
        }
        exo = null
        b.playerView.player = null
    }

    /** Makes the controller's next/previous buttons switch channels / episodes. */
    private fun zappingPlayer(player: ExoPlayer): Player = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                super.isCommandAvailable(command)

        override fun seekToNext() = zap(1)
        override fun seekToPrevious() = zap(-1)
    }

    // ---------- playing ----------

    private fun isLive() = items.getOrNull(index)?.kind == ItemKind.LIVE

    private fun startItem(i: Int, position: Long, finishedPrevious: Boolean = false) {
        if (i != index) extraSubs.clear() // loaded subtitle files belong to one video
        saveProgress(finished = finishedPrevious) // remember where we were in the previous item
        index = i
        playToken++
        val item = items[i]
        attempts = buildAttempts(item)
        attempt = 0
        retries = 0
        b.error.visibility = View.GONE
        b.statusPill.visibility = View.GONE
        b.nextCard.visibility = View.GONE
        nextCancelledFor = -1
        updateTitles()
        updateControls()
        loadEpg(item)
        stripAdapter.highlighted = if (item.kind == ItemKind.EPISODE) i else -1
        listAdapter.highlighted = i

        var start = position
        var resumed = false
        if (start == PlayerQueue.RESUME) {
            val saved = profileId?.let { store.progressFor(it, item) }
            if (item.kind != ItemKind.LIVE && saved != null && saved.position > 10_000 && saved.percent < 95) {
                start = saved.position
                resumed = true
            } else {
                start = C.TIME_UNSET
            }
        }
        when {
            resumed -> showInfo("متابعة من ${DetailsActivity.formatTime(start)}")
            items.size > 1 -> showInfo(
                "${i + 1}   ${item.name}" + (profileId?.let { pid -> com.tvplayer.app.data.Epg.now(pid, item.id) }?.let { "\nالآن: ${it.title}" } ?: "")
            )
        }
        if (item.kind == ItemKind.LIVE) profileId?.let { store.addRecent(it, item) }
        startAttempt(start)
    }

    private fun startAttempt(position: Long = C.TIME_UNSET) {
        if (casting) {
            castLoad(items[index], if (position > 0) position else 0L)
            return
        }
        val player = exo ?: return
        if (attempts.isEmpty()) { showError("الرابط فاضي"); return }
        val (url, mime) = attempts[attempt]
        player.setMediaSource(buildSource(items[index], url, mime), if (position > 0) position else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildAttempts(item: Channel): List<Pair<String, String?>> {
        val list = ArrayList<Pair<String, String?>>()
        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            val u = Http.fixUrl(raw)
            val mime = guessMime(u)
            list += u to mime
            val path = u.lowercase().substringBefore('?')
            // Unknown extension: if the first try fails, force HLS (common for IPTV links).
            if (mime == null && !u.startsWith("rtsp", true) && PROGRESSIVE_EXT.none { path.endsWith(it) }) {
                list += u to MimeTypes.APPLICATION_M3U8
            }
        }
        add(item.url)
        add(item.fallbackUrl)
        return list.distinct()
    }

    private fun guessMime(url: String): String? {
        val l = url.lowercase()
        val path = l.substringBefore('?')
        return when {
            path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.contains(".ism") -> MimeTypes.APPLICATION_SS
            l.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }

    private fun buildSource(item: Channel, url: String, mime: String?): MediaSource {
        val headers = HashMap(item.headers)
        val ua = headers.remove("User-Agent") ?: Http.ua(PlayerQueue.userAgent)
        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        // DefaultDataSource = http(s) through OkHttp, plus local files / content:// (subtitle files).
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(this, http)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
            .setSubtitleConfigurations(extraSubs.toList())
            .apply { if (mime != null) setMimeType(mime) }
            .build()
        return DefaultMediaSourceFactory(dataSource, extractors).createMediaSource(mediaItem)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    retries = 0
                    b.error.visibility = View.GONE
                    b.statusPill.visibility = View.GONE
                }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = applyAspect()
    }

    private fun handleError(error: PlaybackException) {
        val player = exo ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val pos = if (isLive()) C.TIME_UNSET else player.currentPosition
        if (attempt + 1 < attempts.size) {
            attempt++
            startAttempt(pos)
            return
        }
        if (retries < MAX_RETRIES) {
            retries++
            attempt = 0
            showStatus("انقطع الاتصال… إعادة المحاولة ($retries/$MAX_RETRIES)")
            val token = playToken
            handler.postDelayed({ if (token == playToken) startAttempt(pos) }, 1500L * retries)
            return
        }
        b.statusPill.visibility = View.GONE
        showError("تعذّر تشغيل البث\n${error.errorCodeName}\n\nجرّب قناة ثانية أو غيّر صيغة البث (ts / m3u8) من إعدادات السيرفر")
    }

    private fun onEnded() {
        val item = items[index]
        when {
            item.kind == ItemKind.LIVE && retries < MAX_RETRIES -> {
                retries++
                startAttempt()
            }
            item.kind == ItemKind.EPISODE && nextEpisodeIndex() >= 0 && nextCancelledFor == index -> {
                saveProgress(finished = true)
                b.playerView.showController()
            }
            item.kind == ItemKind.EPISODE && nextEpisodeIndex() >= 0 -> playNextEpisode()
            item.kind != ItemKind.LIVE -> {
                saveProgress(finished = true)
                finish()
            }
        }
    }

    // ---------- continue watching ----------

    private fun saveProgress(finished: Boolean = false) {
        val p: Player = (if (casting) castPlayer else exo) ?: return
        val pid = profileId ?: return
        val item = items.getOrNull(index) ?: return
        if (item.kind == ItemKind.LIVE) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0) return
        if (finished || pos > dur * 0.95) {
            store.removeProgress(pid, item.watchKey)
            // Finished an episode: keep the series in "continue watching", pointing at the next one.
            val next = items.getOrNull(index + 1)
            if (item.kind == ItemKind.EPISODE && next != null && next.watchKey == item.watchKey) {
                store.saveProgress(pid, next, 0, 0)
            }
        } else if (pos > 5_000) {
            store.saveProgress(pid, item, pos, dur)
        }
    }

    // ---------- episodes / channels panel ----------

    private fun openList() {
        if (items.size < 2) return
        closePanel()
        b.playerView.hideController()
        b.listTitle.text = if (items.first().kind == ItemKind.LIVE) "القنوات" else "الحلقات"
        listAdapter.submit(items)
        listAdapter.highlighted = index
        b.listPanel.visibility = View.VISIBLE
        (b.listItems.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, 200)
        b.listItems.post { b.listItems.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun closeList() {
        b.listPanel.visibility = View.GONE
        b.playerView.requestFocus()
    }

    // ---------- Chromecast ----------

    private fun setupCast() {
        if (Device.isTv(this)) return // TV boxes play on their own screen
        val ctx = try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
        } catch (e: Exception) {
            null // no Google Play services on this device
        } ?: return
        try {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(applicationContext, ctl.cast)
            ctl.cast.visibility = View.VISIBLE
            castPlayer = androidx.media3.cast.CastPlayer(ctx).apply {
                setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = startCasting()
                    override fun onCastSessionUnavailable() = stopCasting()
                })
            }
            if (castPlayer?.isCastSessionAvailable == true) handler.post { startCasting() }
        } catch (e: Exception) {
            ctl.cast.visibility = View.GONE
            castPlayer = null
        }
    }

    private fun castDeviceName(): String = try {
        com.google.android.gms.cast.framework.CastContext.getSharedInstance(applicationContext)
            .sessionManager.currentCastSession?.castDevice?.friendlyName
    } catch (e: Exception) {
        null
    } ?: "التلفزيون"

    /** Moves playback from this device to the Chromecast, at the same spot. */
    private fun startCasting() {
        val cp = castPlayer ?: return
        val item = items.getOrNull(index) ?: return
        val pos = if (isLive()) 0L else (exo?.currentPosition ?: 0L)
        casting = true
        exo?.pause()
        b.playerView.player = cp
        b.castOverlay.text = "يتم العرض على ${castDeviceName()}"
        b.castOverlay.visibility = View.VISIBLE
        castLoad(item, pos)
        b.playerView.showController()
    }

    /** Back from the Chromecast to this device, at the same spot. */
    private fun stopCasting() {
        if (!casting) return
        val pos = castPlayer?.currentPosition ?: 0L
        casting = false
        b.castOverlay.visibility = View.GONE
        val e = exo ?: return
        b.playerView.player = if (items.size > 1) zappingPlayer(e) else e
        if (!isLive() && pos > 0) e.seekTo(pos)
        e.playWhenReady = true
        showInfo("رجع العرض على هذا الجهاز")
    }

    /** The Chromecast plays HLS best, so prefer the .m3u8 link when there is one. */
    private fun castUrl(item: Channel): String {
        val links = listOfNotNull(item.url, item.fallbackUrl).filter { it.isNotBlank() }.map { Http.fixUrl(it) }
        return links.firstOrNull { it.contains("m3u8", ignoreCase = true) } ?: links.firstOrNull() ?: item.url
    }

    private fun castMime(url: String, item: Channel): String {
        val path = url.lowercase().substringBefore('?')
        return when {
            path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            path.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            item.kind == ItemKind.LIVE -> MimeTypes.APPLICATION_M3U8
            else -> MimeTypes.VIDEO_MP4
        }
    }

    private fun castLoad(item: Channel, position: Long) {
        val cp = castPlayer ?: return
        val url = castUrl(item)
        val meta = MediaMetadata.Builder()
            .setTitle(item.seriesName ?: item.name)
            .apply { if (item.seriesName != null) setSubtitle(item.name) }
            .apply { item.logo?.takeIf { it.isNotBlank() }?.let { setArtworkUri(android.net.Uri.parse(it)) } }
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(castMime(url, item))
            .setMediaMetadata(meta)
            .build()
        try {
            cp.setMediaItem(mediaItem, position)
            cp.prepare()
            cp.playWhenReady = true
            b.castOverlay.text = "يتم العرض على ${castDeviceName()}\n${item.seriesName ?: item.name}"
        } catch (e: Exception) {
            showInfo("ما قدرت أرسل هذا البث للتلفزيون")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
    }

    // ---------- settings ----------

    private data class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean, val supported: Boolean = true)

    private fun tracksOf(type: Int, includeUnsupported: Boolean = false): List<TrackOpt> {
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOpt>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                val ok = g.isTrackSupported(i)
                if (!ok && !includeUnsupported) continue
                val f = g.getTrackFormat(i)
                var label = trackLabel(f, type, out.size + 1)
                if (!ok) label += "  ⚠ صيغة مش مدعومة (${f.codecs ?: f.sampleMimeType ?: "?"})"
                out += TrackOpt(label, g, i, g.isTrackSelected(i), ok)
            }
        }
        if (type == C.TRACK_TYPE_VIDEO) return out.sortedByDescending { it.group.getTrackFormat(it.index).height }
        val counts = out.groupingBy { it.label }.eachCount()
        val seen = HashMap<String, Int>()
        return out.map { t ->
            if ((counts[t.label] ?: 0) < 2) t
            else t.copy(label = t.label.replace(Regex("^([^⚠(]+?)(\\s*)(\\(|  ⚠|$)")) { m ->
                val k = (seen[t.label] ?: 0) + 1
                seen[t.label] = k
                "${m.groupValues[1]} $k${m.groupValues[2]}${m.groupValues[3]}"
            })
        }
    }

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale(code).getDisplayLanguage(Locale("ar")).ifBlank { code }
    }

    /** Short, readable track name: "العربية", "الإنجليزية (للصم)", "1080p (4.2 Mbps)". */
    private fun trackLabel(f: Format, type: Int, n: Int): String {
        if (type == C.TRACK_TYPE_VIDEO) return buildString {
            append(if (f.height > 0) "${f.height}p" else "جودة $n")
            if (f.bitrate > 0) append("  (${String.format(Locale.US, "%.1f", f.bitrate / 1_000_000f)} Mbps)")
        }
        val lang = languageName(f.language)
        val english = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).getDisplayLanguage(Locale.ENGLISH) }
        // The server's own label, unless it just repeats the language.
        val own = f.label?.trim()?.takeIf { l ->
            l.isNotEmpty() && !l.equals(english, true) && !l.equals(lang, true) && !l.equals(f.language, true)
        }
        val base = listOfNotNull(lang, own).joinToString(" • ").ifBlank { if (type == C.TRACK_TYPE_AUDIO) "صوت $n" else "ترجمة $n" }
        val extras = ArrayList<String>()
        if (type == C.TRACK_TYPE_TEXT) {
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) extras += "إجبارية"
            if (f.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0) extras += "للصم"
        } else {
            when (f.channelCount) { 1 -> "Mono"; 6 -> "5.1"; 8 -> "7.1"; else -> null }?.let { extras += it }
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) extras += "وصف صوتي"
        }
        return if (extras.isEmpty()) base else "$base (${extras.joinToString("، ")})"
    }

    // ---------- side panel (settings, subtitles & audio, screen size…) ----------

    private class PanelRow(
        val label: String,
        val value: String? = null,
        /** null = no check column, true/false = selected or not. */
        val checked: Boolean? = null,
        val header: Boolean = false,
        val chevron: Boolean = false,
        val action: (() -> Unit)? = null,
    )

    private var panelBackAction: (() -> Unit)? = null
    private val panelOpen get() = b.settingsPanel.isVisible

    private fun showPanel(title: String, rows: List<PanelRow>, back: (() -> Unit)? = null) {
        b.playerView.hideController()
        closeStrip()
        b.nextCard.visibility = View.GONE
        val dm = resources.displayMetrics
        b.settingsPanel.layoutParams = b.settingsPanel.layoutParams.apply {
            width = minOf((400 * dm.density).toInt(), (dm.widthPixels * 0.88f).toInt())
        }
        b.panelTitle.text = title
        panelBackAction = back
        b.panelBack.isVisible = back != null
        b.panelRows.removeAllViews()
        var first: View? = null
        var selected: View? = null
        for (r in rows) {
            if (r.header) {
                val t = android.widget.TextView(this).apply {
                    text = r.label
                    setTextColor(0xFF8C98AB.toInt())
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    val pad = (12 * dm.density).toInt()
                    setPadding(pad, (if (b.panelRows.childCount == 0) 4 else 18).let { (it * dm.density).toInt() }, pad, (6 * dm.density).toInt())
                }
                b.panelRows.addView(t)
                continue
            }
            val v = layoutInflater.inflate(R.layout.item_panel_row, b.panelRows, false)
            v.findViewById<android.widget.TextView>(R.id.rowLabel).text = r.label
            v.findViewById<android.widget.TextView>(R.id.rowValue).apply {
                text = r.value.orEmpty()
                isVisible = !r.value.isNullOrBlank()
            }
            v.findViewById<View>(R.id.rowCheck).visibility = when (r.checked) {
                null -> View.GONE
                true -> View.VISIBLE
                false -> View.INVISIBLE
            }
            v.findViewById<View>(R.id.rowChevron).isVisible = r.chevron
            if (r.action != null) v.setOnClickListener { r.action.invoke() } else v.isEnabled = false
            b.panelRows.addView(v)
            if (first == null && r.action != null) first = v
            if (r.checked == true && selected == null) selected = v
        }
        b.panelScrim.visibility = View.VISIBLE
        b.settingsPanel.visibility = View.VISIBLE
        b.panelScroll.scrollTo(0, 0)
        (selected ?: first)?.let { v -> v.post { v.requestFocus() } }
    }

    private fun closePanel() {
        if (!panelOpen) return
        b.settingsPanel.visibility = View.GONE
        b.panelScrim.visibility = View.GONE
        panelBackAction = null
        b.playerView.requestFocus()
    }

    /** After choosing an option: back to the page we came from, or close. */
    private fun done(back: (() -> Unit)?) {
        if (back != null) back() else closePanel()
    }

    private fun subtitleSummary(): String {
        val p = exo ?: return "—"
        val subs = tracksOf(C.TRACK_TYPE_TEXT, includeUnsupported = true)
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return when {
            subs.isEmpty() -> "غير متوفرة"
            off -> "إيقاف"
            else -> subs.firstOrNull { it.selected }?.label?.substringBefore("  ⚠") ?: "إيقاف"
        }
    }

    private fun showSettings() {
        val p = exo ?: return
        val height = p.videoSize.height
        val audioNow = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "افتراضي"
        val rows = arrayListOf(
            PanelRow("الجودة", "${if (height > 0) "${height}p الآن  •  " else ""}${QUALITY_NAMES[qualityIndex()]}", chevron = true) { qualityPage(::showSettings) },
            PanelRow("الترجمة والصوت", "${subtitleSummary()}  •  $audioNow", chevron = true) { subtitlePage(::showSettings) },
            PanelRow("حجم الشاشة", ASPECTS[store.resizeIndex.coerceIn(0, ASPECTS.size - 1)].name, chevron = true) { aspectPage(::showSettings) },
            PanelRow("حجم الترجمة", SUB_NAMES[SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)], chevron = true) { subSizePage(::showSettings) },
        )
        if (!Device.isTv(this)) {
            rows += PanelRow("اتجاه الشاشة", ORIENTATIONS[store.orientation.coerceIn(0, ORIENTATIONS.size - 1)].first, chevron = true) { orientationPage(::showSettings) }
        }
        if (!isLive()) {
            rows += PanelRow("سرعة التشغيل", "${p.playbackParameters.speed}x", chevron = true) { speedPage(::showSettings) }
        }
        showPanel("الإعدادات", rows)
    }

    private fun qualityIndex() = QUALITY_CAPS.indexOf(store.maxQuality).coerceAtLeast(0)

    private fun withQualityCap(builder: androidx.media3.common.TrackSelectionParameters.Builder, cap: Int) =
        if (cap > 0) builder.setMaxVideoSize(Int.MAX_VALUE, cap) else builder.clearVideoSizeConstraints()

    private fun qualityPage(back: (() -> Unit)? = null) {
        val p = exo ?: return
        val tracks = tracksOf(C.TRACK_TYPE_VIDEO)
        val overridden = p.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        val rows = ArrayList<PanelRow>()
        rows += PanelRow("الحد الأقصى للجودة", header = true)
        QUALITY_NAMES.forEachIndexed { i, name ->
            rows += PanelRow(name, checked = !overridden && i == qualityIndex()) {
                store.maxQuality = QUALITY_CAPS[i]
                p.trackSelectionParameters = withQualityCap(
                    p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO), QUALITY_CAPS[i],
                ).build()
                showInfo("الجودة: $name")
                done(back)
            }
        }
        val h = p.videoSize.height
        rows += PanelRow(
            if (tracks.size <= 1) "السيرفر بيبعت جودة وحدة بس" + (if (h > 0) " (${h}p)" else "")
            else "الجودات المتوفرة في هذا البث (${tracks.size})",
            header = true,
        )
        if (tracks.size > 1) tracks.forEach { t ->
            rows += PanelRow(t.label, checked = overridden && t.selected) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                showInfo("الجودة: ${t.label}")
                done(back)
            }
        }
        showPanel("الجودة", rows, back)
    }

    /** Subtitles and audio together, like the big streaming apps. */
    private fun subtitlePage(back: (() -> Unit)? = null) {
        val p = exo ?: return
        val self = { subtitlePage(back) }
        val subs = tracksOf(C.TRACK_TYPE_TEXT, includeUnsupported = true)
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || subs.none { it.selected }
        val rows = ArrayList<PanelRow>()
        rows += PanelRow(
            when {
                subs.isEmpty() && p.playbackState != Player.STATE_READY -> "الترجمة — لسا عم يحمّل الفيديو"
                subs.isEmpty() -> "الترجمة — ما في ترجمة داخل هذا البث"
                else -> "الترجمة"
            },
            header = true,
        )
        rows += PanelRow("إيقاف", checked = off) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            showInfo("الترجمة: إيقاف")
            done(back)
        }
        subs.forEach { t ->
            rows += PanelRow(t.label.substringBefore("  ⚠"), if (t.supported) null else "صيغة مش مدعومة — جرّب ملف ترجمة", checked = !off && t.selected) {
                if (!t.supported) {
                    Toast.makeText(this, "هاي الترجمة بصيغة ما بيدعمها المشغّل. جرّب ملف ترجمة من الجهاز", Toast.LENGTH_LONG).show()
                    return@PanelRow
                }
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                showInfo("الترجمة: ${t.label}")
                done(back)
            }
        }
        rows += PanelRow("تحميل ملف ترجمة من الجهاز", "srt • vtt • ass") {
            closePanel()
            try {
                pickSubtitle.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Toast.makeText(this, "ما في مدير ملفات على هذا الجهاز", Toast.LENGTH_LONG).show()
            }
        }
        rows += PanelRow("حجم الترجمة", SUB_NAMES[SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)], chevron = true) { subSizePage(self) }

        val audio = tracksOf(C.TRACK_TYPE_AUDIO)
        rows += PanelRow(if (audio.size <= 1) "الصوت — مسار واحد" else "الصوت", header = true)
        audio.forEach { t ->
            rows += PanelRow(t.label, checked = t.selected) {
                if (audio.size > 1) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                        .build()
                    showInfo("الصوت: ${t.label}")
                }
                done(back)
            }
        }
        showPanel("الترجمة والصوت", rows, back)
    }

    /** Subtitle files the user loaded from the device for the current item. */
    private val extraSubs = ArrayList<MediaItem.SubtitleConfiguration>()

    private val pickSubtitle = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitleFile(uri)
    }

    /** Adds a subtitle file from the device and reloads the video at the same spot. */
    private fun addSubtitleFile(uri: android.net.Uri) {
        val p = exo ?: return
        var name = "ترجمة"
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        extraSubs += MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("ar")
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        startAttempt(if (isLive()) C.TIME_UNSET else p.currentPosition)
        showInfo("انضافت الترجمة: $name")
    }

    private fun aspectPage(back: (() -> Unit)? = null) {
        val cur = store.resizeIndex.coerceIn(0, ASPECTS.size - 1)
        showPanel("حجم الشاشة", ASPECTS.mapIndexed { i, a ->
            PanelRow(a.name, checked = i == cur) {
                store.resizeIndex = i
                applyAspect(announce = true)
                done(back)
            }
        }, back)
    }

    private fun cycleResize() {
        store.resizeIndex = (store.resizeIndex.coerceIn(0, ASPECTS.size - 1) + 1) % ASPECTS.size
        applyAspect(announce = true)
    }

    /** Applies the chosen screen size; forced ratios are re-applied after the player sets its own. */
    private fun applyAspect(announce: Boolean = false) {
        val a = ASPECTS[store.resizeIndex.coerceIn(0, ASPECTS.size - 1)]
        b.playerView.resizeMode = a.resize
        val frame = b.playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        if (frame != null) {
            val ratio = if (a.ratio > 0) a.ratio else exo?.videoSize?.let { v ->
                if (v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 0f
            } ?: 0f
            if (ratio > 0) frame.post { frame.setAspectRatio(ratio) }
        }
        if (announce) showInfo("حجم الشاشة: ${a.name}")
    }

    private fun orientationPage(back: (() -> Unit)? = null) {
        val cur = store.orientation.coerceIn(0, ORIENTATIONS.size - 1)
        showPanel("اتجاه الشاشة", ORIENTATIONS.mapIndexed { i, o ->
            PanelRow(o.first, checked = i == cur) {
                store.orientation = i
                requestedOrientation = o.second
                done(back)
            }
        }, back)
    }

    private fun subSizePage(back: (() -> Unit)? = null) {
        val cur = SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)
        showPanel("حجم الترجمة", SUB_NAMES.mapIndexed { i, n ->
            PanelRow(n, checked = i == cur) {
                store.subtitleScale = SUB_SCALES[i]
                applySubtitleStyle()
                done(back)
            }
        }, back)
    }

    private fun speedPage(back: (() -> Unit)? = null) {
        val p = exo ?: return
        showPanel("سرعة التشغيل", SPEEDS.map { sp ->
            PanelRow(if (sp == 1f) "عادية (1x)" else "${sp}x", checked = p.playbackParameters.speed == sp) {
                p.setPlaybackSpeed(sp)
                showInfo("السرعة: ${sp}x")
                done(back)
            }
        }, back)
    }

    // ---------- up next ----------

    /** The viewer pressed "cancel" on the up-next card for this episode. */
    private var nextCancelledFor = -1

    private fun playNextEpisode() {
        val n = nextEpisodeIndex()
        if (n < 0) return
        b.nextCard.visibility = View.GONE
        startItem(n, 0, finishedPrevious = true)
    }

    private val upNextTick = object : Runnable {
        override fun run() {
            updateUpNext()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateUpNext() {
        val p = exo
        val n = nextEpisodeIndex()
        val dur = p?.duration ?: C.TIME_UNSET
        val left = if (p != null && dur != C.TIME_UNSET) dur - p.currentPosition else Long.MAX_VALUE
        val show = p != null && !casting && n >= 0 && nextCancelledFor != index &&
            dur > 120_000 && p.playbackState == Player.STATE_READY && left in 1..UP_NEXT_MS &&
            !panelOpen && !stripOpen && !b.listPanel.isVisible
        if (show) {
            b.nextName.text = items[n].name
            b.nextPlay.text = "▶  شغّل الآن (${(left + 999) / 1000})"
            if (!b.nextCard.isVisible) {
                b.nextCard.visibility = View.VISIBLE
                if (Device.isTv(this)) b.nextPlay.requestFocus()
            }
        } else if (b.nextCard.isVisible) {
            b.nextCard.visibility = View.GONE
        }
    }

    private fun applySubtitleStyle() {
        b.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, 0x80000000.toInt(), Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
                )
            )
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * store.subtitleScale)
        }
    }

    // ---------- UI helpers ----------

    private fun zap(delta: Int) {
        if (items.size < 2) return
        val from = if (pendingIndex >= 0) pendingIndex else index
        pendingIndex = (from + delta).mod(items.size)
        showInfo("${pendingIndex + 1}   ${items[pendingIndex].name}")
        handler.removeCallbacks(zapRunnable)
        handler.postDelayed(zapRunnable, 500)
    }

    private val hideLevel = Runnable { b.gestureBox.visibility = View.GONE }

    private fun showLevel(volume: Boolean, pct: Int) {
        b.gestureIcon.setImageResource(if (volume) com.tvplayer.app.R.drawable.ic_volume else com.tvplayer.app.R.drawable.ic_brightness)
        b.gestureValue.text = "$pct%"
        b.gestureBar.progress = pct
        b.gestureBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideLevel)
        handler.postDelayed(hideLevel, 900)
    }

    private fun showInfo(text: String) {
        b.info.text = text
        b.info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3000)
    }

    private fun showStatus(text: String) {
        b.statusText.text = text
        b.statusPill.visibility = View.VISIBLE
    }

    private fun showError(text: String) {
        b.error.text = text
        b.error.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Strip open: the remote moves between episodes; OK plays the focused one.
        if ((stripOpen || panelOpen || b.nextCard.hasFocus()) && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && !b.listPanel.isVisible) {
            val controllerShown = b.playerView.isControllerFullyVisible
            when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (isLive() && items.size > 1) {
                    if (numberBuffer.length < 4) numberBuffer += (event.keyCode - KeyEvent.KEYCODE_0).toString()
                    showInfo("${numberBuffer}_")
                    handler.removeCallbacks(numberRunnable)
                    handler.postDelayed(numberRunnable, 1500)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!controllerShown && isLive()) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controllerShown && isLive()) { zap(1); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showSettings(); return true }
                KeyEvent.KEYCODE_GUIDE -> { openEpisodesOrList(); return true }
                KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_TV_ZOOM_MODE -> { cycleResize(); return true }
                KeyEvent.KEYCODE_CAPTIONS -> { subtitlePage(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controllerShown) {
                    if (isLive() && items.size > 1) openList() else b.playerView.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
