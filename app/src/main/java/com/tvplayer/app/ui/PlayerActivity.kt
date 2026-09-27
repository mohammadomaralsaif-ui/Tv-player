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
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private companion object {
        const val MAX_RETRIES = 4
        const val SAVE_EVERY_MS = 10_000L
        val PROGRESSIVE_EXT = listOf(".ts", ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".mp3", ".aac", ".flv")
        val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )
        val RESIZE_NAMES = arrayOf("ملاءمة (بدون قص)", "تكبير (ملء مع قص)", "تمديد (ملء بدون قص)")
        val QUALITY_CAPS = intArrayOf(0, 2160, 1080, 720, 480, 360)
        val QUALITY_NAMES = arrayOf("تلقائي (أفضل جودة متاحة)", "4K كحد أقصى", "Full HD 1080p كحد أقصى", "HD 720p كحد أقصى", "480p كحد أقصى (توفير نت)", "360p كحد أقصى")
        val SUB_SCALES = floatArrayOf(0.75f, 1f, 1.3f, 1.6f, 2f)
        val SUB_NAMES = arrayOf("صغير", "عادي", "كبير", "كبير جداً", "ضخم")
        val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }

    private lateinit var b: ActivityPlayerBinding
    private lateinit var store: ProfileStore
    private lateinit var listAdapter: ChannelAdapter
    private var exo: ExoPlayer? = null
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
        b.playerView.resizeMode = RESIZE_MODES[store.resizeIndex.coerceIn(0, RESIZE_MODES.size - 1)]
        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v -> if (!b.listPanel.isVisible) b.topBar.visibility = v }
        )
        applySubtitleStyle()

        b.btnBack.setOnClickListener { finish() }
        b.btnSettings.setOnClickListener { showSettings() }
        b.btnList.isVisible = items.size > 1
        b.btnList.text = if (items.first().kind == ItemKind.LIVE) "☰ القنوات" else "☰ الحلقات"
        b.btnList.setOnClickListener { openList() }

        listAdapter = ChannelAdapter(
            onClick = { _, pos -> closeList(); if (pos != index) startItem(pos, PlayerQueue.RESUME) },
            onLongClick = {},
            progressOf = { c -> profileId?.let { store.progressFor(it, c)?.percent } },
        )
        b.listItems.layoutManager = LinearLayoutManager(this)
        b.listItems.adapter = listAdapter

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.listPanel.isVisible -> closeList()
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
        b.playerView.player = if (items.size > 1) zappingPlayer(player) else player
        startItem(index, resumePosition)
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
        saveProgress() // remember where we were in the previous item
        index = i
        playToken++
        val item = items[i]
        attempts = buildAttempts(item)
        attempt = 0
        retries = 0
        b.error.visibility = View.GONE
        b.title.text = item.name
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
            items.size > 1 -> showInfo("${i + 1}   ${item.name}")
        }
        if (item.kind == ItemKind.LIVE) profileId?.let { store.addRecent(it, item) }
        startAttempt(start)
    }

    private fun startAttempt(position: Long = C.TIME_UNSET) {
        val player = exo ?: return
        if (attempts.isEmpty()) { showError("الرابط فاضي"); return }
        val (url, mime) = attempts[attempt]
        player.setMediaSource(buildSource(items[index], url, mime), if (position > 0) position else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildAttempts(item: Channel): List<Pair<String, String?>> {
        val list = ArrayList<Pair<String, String?>>()
        fun add(u: String?) {
            if (u.isNullOrBlank()) return
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
        val dataSource = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).build())
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
        val p = exo ?: return
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

    // ---------- settings ----------

    private data class TrackOpt(val label: String, val group: Tracks.Group, val index: Int, val selected: Boolean)

    private fun tracksOf(type: Int): List<TrackOpt> {
        val p = exo ?: return emptyList()
        val out = ArrayList<TrackOpt>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out += TrackOpt(trackLabel(g.getTrackFormat(i), type, out.size + 1), g, i, g.isTrackSelected(i))
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
        val subs = tracksOf(C.TRACK_TYPE_TEXT)
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val subNow = if (textOff) "إيقاف" else subs.firstOrNull { it.selected }?.label ?: if (subs.isEmpty()) "غير متوفرة" else "إيقاف"
        val audioNow = tracksOf(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "افتراضي"

        val labels = arrayListOf(
            "🎞  الجودة: ${if (height > 0) "${height}p الآن" else "—"} • ${QUALITY_NAMES[qualityIndex()]}",
            "💬  الترجمة: $subNow",
            "🔊  الصوت: $audioNow",
            "🖥  حجم الشاشة: ${RESIZE_NAMES[store.resizeIndex.coerceIn(0, RESIZE_NAMES.size - 1)]}",
            "🔠  حجم الترجمة: ${SUB_NAMES[SUB_SCALES.indexOfFirst { it == store.subtitleScale }.coerceAtLeast(1)]}",
        )
        val actions = arrayListOf<() -> Unit>(::chooseQuality, ::chooseSubtitle, ::chooseAudio, ::chooseResize, ::chooseSubSize)
        if (!isLive()) {
            labels += "⏩  سرعة التشغيل: ${p.playbackParameters.speed}x"
            actions += ::chooseSpeed
        }
        if (items.size > 1) {
            labels += if (isLive()) "☰  قائمة القنوات" else "☰  قائمة الحلقات"
            actions += ::openList
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

    private fun chooseSubtitle() {
        val p = exo ?: return
        val tracks = tracksOf(C.TRACK_TYPE_TEXT)
        if (tracks.isEmpty()) {
            Toast.makeText(this, "ما في ترجمة بهاد الفيديو", Toast.LENGTH_SHORT).show()
            return
        }
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val labels = arrayOf("إيقاف الترجمة") + tracks.map { it.label }
        val checked = if (off) 0 else (tracks.indexOfFirst { it.selected } + 1).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("الترجمة")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val params = p.trackSelectionParameters.buildUpon()
                p.trackSelectionParameters = if (which == 0) {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                } else {
                    val t = tracks[which - 1]
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                        .build()
                }
                showInfo("الترجمة: ${labels[which]}")
                d.dismiss()
            }
            .show()
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
            .setSingleChoiceItems(RESIZE_NAMES, store.resizeIndex) { d, which ->
                store.resizeIndex = which
                b.playerView.resizeMode = RESIZE_MODES[which]
                showInfo("حجم الشاشة: ${RESIZE_NAMES[which]}")
                d.dismiss()
            }
            .show()
    }

    private fun cycleResize() {
        val next = (store.resizeIndex + 1) % RESIZE_MODES.size
        store.resizeIndex = next
        b.playerView.resizeMode = RESIZE_MODES[next]
        showInfo("حجم الشاشة: ${RESIZE_NAMES[next]}")
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
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (!controllerShown && isLive()) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!controllerShown && isLive()) { zap(1); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showSettings(); return true }
                KeyEvent.KEYCODE_GUIDE -> { openList(); return true }
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
