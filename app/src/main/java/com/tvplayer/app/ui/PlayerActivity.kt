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
import androidx.appcompat.app.AlertDialog
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
        stripOpen = !stripOpen
        b.episodesBar.visibility = if (stripOpen) View.VISIBLE else View.GONE
        b.playerView.showController()
        if (stripOpen) {
            b.episodesStrip.scrollToPosition(index)
            b.episodesStrip.post { b.episodesStrip.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
        }
    }

    /** Current values on the top-bar buttons: subtitles and quality. */
    private fun updateChips() {
        val narrow = Device.isNarrow(this)
        val subs = tracksOf(C.TRACK_TYPE_TEXT, includeUnsupported = true)
        val off = exo?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        val subNow = when {
            subs.isEmpty() -> "—"
            off -> "إيقاف"
            else -> subs.firstOrNull { it.selected }?.label?.substringBefore(" •")?.substringBefore("  ⚠") ?: "إيقاف"
        }
        b.btnSubs.text = if (narrow) "CC" else "الترجمة: $subNow"
        val h = exo?.videoSize?.height ?: 0
        b.btnQuality.text = if (h > 0) "${h}p" else "الجودة"
    }

    /** Title / second line: series + episode, or channel + what's on now. */
    private fun updateTitles() {
        val item = items.getOrNull(index) ?: return
        when {
            item.kind == ItemKind.EPISODE && item.seriesName != null -> {
                b.title.text = item.seriesName
                b.subtitle.text = item.name
                b.subtitle.visibility = View.VISIBLE
            }
            item.kind == ItemKind.LIVE -> {
                b.title.text = if (items.size > 1) "${index + 1}   ${item.name}" else item.name
                val pid = profileId
                val now = if (pid != null) com.tvplayer.app.data.Epg.now(pid, item.id) else null
                b.subtitle.text = now?.let { "الآن: ${it.title}  (${it.timeRange()})" }.orEmpty()
                b.subtitle.visibility = if (now != null) View.VISIBLE else View.GONE
            }
            else -> {
                b.title.text = item.name
                b.subtitle.visibility = View.GONE
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
                        else if (b.playerView.isControllerFullyVisible) b.playerView.hideController()
                        else b.playerView.showController()
                    },
                )
            )
        }
        applyAspect()
        if (!Device.isTv(this)) requestedOrientation = ORIENTATIONS[store.orientation.coerceIn(0, ORIENTATIONS.size - 1)].second
        b.btnAspect.setOnClickListener { cycleResize() }
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                if (!b.listPanel.isVisible) {
                    b.topBar.visibility = v
                    if (v == View.VISIBLE) updateChips()
                    // The episodes strip only shows when asked for (☰ الحلقات), never on its own.
                    if (v != View.VISIBLE) {
                        stripOpen = false
                        b.episodesBar.visibility = View.GONE
                    }
                }
            }
        )
        b.btnSubs.setOnClickListener { chooseSubtitle() }
        b.btnQuality.setOnClickListener { chooseQuality() }
        applySubtitleStyle()
        setupCast()

        b.btnBack.setOnClickListener { finish() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnList.isVisible = items.size > 1
        b.btnList.text = if (items.first().kind == ItemKind.LIVE) "☰ القنوات" else "☰ الحلقات"
        b.btnList.setOnClickListener { openEpisodesOrList() }

        listAdapter = ChannelAdapter(
            onClick = { _, pos -> closeList(); if (pos != index) startItem(pos, PlayerQueue.RESUME) },
            onLongClick = {},
            progressOf = { c -> profileId?.let { store.progressFor(it, c)?.percent } },
        )
        b.listItems.layoutManager = LinearLayoutManager(this)
        b.listItems.adapter = listAdapter

        // Series: the episodes strip shown with the controls.
        stripAdapter = ChannelAdapter(
            onClick = { _, pos -> if (pos != index) startItem(pos, PlayerQueue.RESUME) },
            onLongClick = {},
            progressOf = { c -> profileId?.let { store.progressFor(it, c)?.percent } },
            subtitleOf = { c -> c.duration.ifBlank { null } },
        ).apply {
            grid = true
            landscape = true
            fixedWidthPx = ((if (Device.isNarrow(this@PlayerActivity)) 170 else 220) * resources.displayMetrics.density).toInt()
            onFocusItem = { b.playerView.showController() } // keep controls up while choosing
        }
        b.episodesStrip.layoutManager = LinearLayoutManager(this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false)
        b.episodesStrip.adapter = stripAdapter
        if (items.first().kind == ItemKind.EPISODE) stripAdapter.submit(items)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.listPanel.isVisible -> closeList()
                    stripOpen -> { stripOpen = false; b.episodesBar.visibility = View.GONE }
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

    private fun startItem(i: Int, position: Long) {
        if (i != index) extraSubs.clear() // loaded subtitle files belong to one video
        saveProgress() // remember where we were in the previous item
        index = i
        playToken++
        val item = items[i]
        attempts = buildAttempts(item)
        attempt = 0
        retries = 0
        b.error.visibility = View.GONE
        updateTitles()
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
            showError("انقطع الاتصال… إعادة المحاولة ($retries/$MAX_RETRIES)")
            val token = playToken
            handler.postDelayed({ if (token == playToken) startAttempt(pos) }, 1500L * retries)
            return
        }
        showError("تعذّر تشغيل البث\n${error.errorCodeName}\n\nجرّب قناة ثانية أو غيّر صيغة البث (ts / m3u8) من إعدادات السيرفر")
    }

    private fun onEnded() {
        val item = items[index]
        when {
            item.kind == ItemKind.LIVE && retries < MAX_RETRIES -> {
                retries++
                startAttempt()
            }
            item.kind == ItemKind.EPISODE && index + 1 < items.size -> {
                saveProgress(finished = true)
                showInfo("الحلقة الجاية…")
                startItem(index + 1, 0)
            }
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
        b.playerView.hideController()
        b.topBar.visibility = View.GONE
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
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(applicationContext, b.btnCast)
            b.btnCast.visibility = View.VISIBLE
            castPlayer = androidx.media3.cast.CastPlayer(ctx).apply {
                setSessionAvailabilityListener(object : androidx.media3.cast.SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = startCasting()
                    override fun onCastSessionUnavailable() = stopCasting()
                })
            }
            if (castPlayer?.isCastSessionAvailable == true) handler.post { startCasting() }
        } catch (e: Exception) {
            b.btnCast.visibility = View.GONE
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
        return if (type == C.TRACK_TYPE_VIDEO) out.sortedByDescending { it.group.getTrackFormat(it.index).height } else out
    }

    private fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        return Locale(code).getDisplayLanguage(Locale("ar")).ifBlank { code }
    }

    private fun trackLabel(f: Format, type: Int, n: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> buildString {
            append(if (f.height > 0) "${f.height}p" else "جودة $n")
            if (f.bitrate > 0) append("  (${String.format(Locale.US, "%.1f", f.bitrate / 1_000_000f)} Mbps)")
        }
        C.TRACK_TYPE_AUDIO -> listOfNotNull(
            f.label, languageName(f.language),
            when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
        ).distinct().joinToString(" • ").ifBlank { "صوت $n" }
        else -> listOfNotNull(f.label, languageName(f.language)).distinct().joinToString(" • ").ifBlank { "ترجمة $n" }
    }

    private fun showSettings() {
        val p = exo ?: return
        b.playerView.hideController()
        val height = p.videoSize.height
        val subs = tracksOf(C.TRACK_TYPE_TEXT, includeUnsupported = true)
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val subNow = if (textOff) "إيقاف" else subs.firstOrNull { it.selected }?.label ?: if (subs.isEmpty()) "غير متوفرة" else "إيقاف"
        val audioNow = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "افتراضي"

        val labels = arrayListOf(
            "🎞  الجودة: ${if (height > 0) "${height}p الآن" else "—"} • ${QUALITY_NAMES[qualityIndex()]}",
            "💬  الترجمة: $subNow",
            "🔊  الصوت: $audioNow",
            "🖥  حجم الشاشة: ${ASPECTS[store.resizeIndex.coerceIn(0, ASPECTS.size - 1)].name}",
            "🔠  حجم الترجمة: ${SUB_NAMES[SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)]}",
        )
        val actions = arrayListOf<() -> Unit>(::chooseQuality, ::chooseSubtitle, ::chooseAudio, ::chooseResize, ::chooseSubSize)
        if (!Device.isTv(this)) {
            labels += "🔄  اتجاه الشاشة: ${ORIENTATIONS[store.orientation.coerceIn(0, ORIENTATIONS.size - 1)].first}"
            actions += ::chooseOrientation
        }
        if (!isLive()) {
            labels += "⏩  سرعة التشغيل: ${p.playbackParameters.speed}x"
            actions += ::chooseSpeed
        }
        if (items.size > 1) {
            labels += if (isLive()) "☰  قائمة القنوات" else "☰  قائمة الحلقات"
            actions += ::openEpisodesOrList
        }
        AlertDialog.Builder(this)
            .setTitle("الإعدادات")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun qualityIndex() = QUALITY_CAPS.indexOf(store.maxQuality).coerceAtLeast(0)

    private fun withQualityCap(builder: androidx.media3.common.TrackSelectionParameters.Builder, cap: Int) =
        if (cap > 0) builder.setMaxVideoSize(Int.MAX_VALUE, cap) else builder.clearVideoSizeConstraints()

    private fun chooseQuality() {
        val p = exo ?: return
        val tracks = tracksOf(C.TRACK_TYPE_VIDEO)
        val labels = ArrayList<String>()
        QUALITY_NAMES.forEach { labels += it }
        tracks.forEach { labels += "▶ بالضبط: ${it.label}" + if (it.selected) "  ✓" else "" }
        val title = when {
            tracks.size <= 1 -> {
                val h = p.videoSize.height
                "الجودة — السيرفر بيبعت جودة وحدة بس" + if (h > 0) " (${h}p)" else ""
            }
            else -> "الجودة — متوفر ${tracks.size} جودات"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), qualityIndex()) { d, which ->
                val params = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (which < QUALITY_CAPS.size) {
                    store.maxQuality = QUALITY_CAPS[which]
                    p.trackSelectionParameters = withQualityCap(params, QUALITY_CAPS[which]).build()
                    showInfo("الجودة: ${QUALITY_NAMES[which]}")
                } else {
                    val t = tracks[which - QUALITY_CAPS.size]
                    p.trackSelectionParameters = params.clearVideoSizeConstraints()
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                        .build()
                    showInfo("الجودة: ${t.label}")
                }
                d.dismiss()
            }
            .show()
    }

    /** Subtitle files the user loaded from the device for the current item. */
    private val extraSubs = ArrayList<MediaItem.SubtitleConfiguration>()

    private val pickSubtitle = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitleFile(uri)
    }

    private fun chooseSubtitle() {
        val p = exo ?: return
        val tracks = tracksOf(C.TRACK_TYPE_TEXT, includeUnsupported = true)
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val labels = ArrayList<String>()
        labels += "إيقاف الترجمة"
        tracks.forEach { labels += it.label }
        labels += "📂  تحميل ملف ترجمة من الجهاز (srt / vtt / ass)…"
        val loadIndex = labels.size - 1
        val checked = if (off || tracks.none { it.selected }) 0 else tracks.indexOfFirst { it.selected } + 1
        val title = when {
            tracks.isEmpty() && p.playbackState != Player.STATE_READY -> "الترجمة — لسا عم يحمّل الفيديو، جرّب بعد ثواني"
            tracks.isEmpty() -> "الترجمة — ما لقيت ترجمة داخل هذا البث"
            else -> "الترجمة — متوفر ${tracks.size}"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                when {
                    which == loadIndex -> try {
                        pickSubtitle.launch(arrayOf("*/*"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "ما في مدير ملفات على هذا الجهاز", Toast.LENGTH_LONG).show()
                    }
                    which == 0 -> {
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        showInfo("الترجمة: إيقاف")
                    }
                    else -> {
                        val t = tracks[which - 1]
                        if (!t.supported) {
                            Toast.makeText(this, "هاي الترجمة بصيغة ما بيدعمها المشغّل. جرّب ملف ترجمة من الجهاز", Toast.LENGTH_LONG).show()
                            return@setSingleChoiceItems
                        }
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                            .build()
                        showInfo("الترجمة: ${t.label}")
                    }
                }
            }
            .show()
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

    private fun chooseAudio() {
        val p = exo ?: return
        val tracks = tracksOf(C.TRACK_TYPE_AUDIO)
        if (tracks.size <= 1) {
            Toast.makeText(this, "في مسار صوت واحد بس", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("الصوت")
            .setSingleChoiceItems(tracks.map { it.label }.toTypedArray(), tracks.indexOfFirst { it.selected }) { d, which ->
                val t = tracks[which]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                showInfo("الصوت: ${t.label}")
                d.dismiss()
            }
            .show()
    }

    private fun chooseResize() {
        AlertDialog.Builder(this)
            .setTitle("حجم الشاشة")
            .setSingleChoiceItems(ASPECTS.map { it.name }.toTypedArray(), store.resizeIndex.coerceIn(0, ASPECTS.size - 1)) { d, which ->
                store.resizeIndex = which
                applyAspect(announce = true)
                d.dismiss()
            }
            .show()
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

    private fun chooseOrientation() {
        AlertDialog.Builder(this)
            .setTitle("اتجاه الشاشة")
            .setSingleChoiceItems(ORIENTATIONS.map { it.first }.toTypedArray(), store.orientation.coerceIn(0, ORIENTATIONS.size - 1)) { d, which ->
                store.orientation = which
                requestedOrientation = ORIENTATIONS[which].second
                d.dismiss()
            }
            .show()
    }

    private fun chooseSubSize() {
        val current = SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("حجم الترجمة")
            .setSingleChoiceItems(SUB_NAMES, current) { d, which ->
                store.subtitleScale = SUB_SCALES[which]
                applySubtitleStyle()
                d.dismiss()
            }
            .show()
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

    private fun chooseSpeed() {
        val p = exo ?: return
        val names = SPEEDS.map { "${it}x" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("سرعة التشغيل")
            .setSingleChoiceItems(names, SPEEDS.indexOfFirst { it == p.playbackParameters.speed }) { d, which ->
                p.setPlaybackSpeed(SPEEDS[which])
                d.dismiss()
            }
            .show()
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

    private fun showError(text: String) {
        b.error.text = text
        b.error.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                KeyEvent.KEYCODE_CAPTIONS -> { chooseSubtitle(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!controllerShown) {
                    if (isLive() && items.size > 1) openList() else b.playerView.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
