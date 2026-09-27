package com.tvplayer.app.ui

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.tvplayer.app.R
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.Details
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.PlayerQueue
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.WatchEntry
import com.tvplayer.app.data.XtreamApi
import com.tvplayer.app.databinding.ActivityDetailsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Movie or series page: poster, plot, cast, rating; for series also seasons and episodes. */
class DetailsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        const val EXTRA_ITEM = "item"
        const val EXTRA_AUTOPLAY = "autoplay_episode"

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
            else String.format(java.util.Locale.US, "%d:%02d", m, sec)
        }
    }

    private lateinit var b: ActivityDetailsBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: ServerProfile
    private lateinit var item: Channel
    private lateinit var episodeAdapter: ChannelAdapter
    private var episodes: List<Channel> = emptyList()
    private var season: String? = null
    private var autoplayEpisode: String? = null

    private val isSeries get() = item.kind == ItemKind.SERIES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        profile = store.get(intent.getStringExtra(EXTRA_PROFILE)) ?: run { finish(); return }
        item = runCatching { Channel.fromJson(JSONObject(intent.getStringExtra(EXTRA_ITEM) ?: "")) }
            .getOrElse { finish(); return }
        autoplayEpisode = if (savedInstanceState == null) intent.getStringExtra(EXTRA_AUTOPLAY) else null

        episodeAdapter = ChannelAdapter(
            onClick = { _, pos -> playEpisode(visibleEpisodes()[pos], PlayerQueue.RESUME) },
            onLongClick = { ep -> Nav.itemOptions(this, store, profile, ep) { refreshState() } },
            progressOf = { ep -> store.progressFor(profile.id, ep)?.percent },
            subtitleOf = { null },
        )
        b.episodes.layoutManager = LinearLayoutManager(this)
        b.episodes.adapter = episodeAdapter
        b.seriesBlock.isVisible = isSeries

        // Show what we already know right away, then fill in from the server.
        show(Details(title = item.seriesName ?: item.name, poster = item.logo, backdrop = null, rating = item.rating, release = item.year))
        b.btnFav.setOnClickListener {
            store.toggleFavorite(profile.id, favItem())
            refreshState()
        }
        b.btnPlay.setOnClickListener { onPlay(fromStart = false) }
        b.btnRestart.setOnClickListener { onPlay(fromStart = true) }
        b.btnPlay.requestFocus()
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::item.isInitialized) refreshState()
    }

    private fun favItem(): Channel =
        if (isSeries) item.copy(name = item.seriesName ?: item.name, kind = ItemKind.SERIES) else item

    private fun load() {
        val api = XtreamApi(profile)
        b.progress.isVisible = true
        lifecycleScope.launch {
            try {
                if (isSeries) {
                    val data = api.seriesInfo(item.id)
                    show(data.details.copy(
                        title = data.details.title.ifBlank { item.name },
                        poster = data.details.poster ?: item.logo,
                    ))
                    episodes = data.episodes
                    buildSeasons()
                    val auto = autoplayEpisode
                    autoplayEpisode = null
                    if (auto != null) episodes.firstOrNull { it.id == auto }?.let { playEpisode(it, PlayerQueue.RESUME) }
                } else {
                    val d = api.vodInfo(item.id)
                    show(d.copy(title = d.title.ifBlank { item.name }, poster = d.poster ?: item.logo))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.error.isVisible = true
                b.error.text = if (isSeries) "تعذّر تحميل الحلقات: ${e.message}" else "ما قدرت أجيب التفاصيل، بس بتقدر تشغّل الفيلم"
            } finally {
                b.progress.isVisible = false
                refreshState()
            }
        }
    }

    private fun show(d: Details) {
        b.title.text = d.title
        val meta = listOfNotNull(
            d.release.take(4).ifBlank { null },
            d.genre.ifBlank { null },
            d.rating.ifBlank { null }?.let { "★ $it" },
            d.duration.ifBlank { null },
        )
        b.meta.text = meta.joinToString("   •   ")
        b.meta.isVisible = meta.isNotEmpty()
        b.plot.text = d.plot
        b.plot.isVisible = d.plot.isNotBlank()
        val people = listOfNotNull(
            d.director.ifBlank { null }?.let { "إخراج: $it" },
            d.cast.ifBlank { null }?.let { "بطولة: $it" },
        )
        b.cast.text = people.joinToString("\n")
        b.cast.isVisible = people.isNotEmpty()
        if (!d.poster.isNullOrBlank()) b.poster.load(d.poster) { placeholder(R.drawable.ic_tv); error(R.drawable.ic_tv) }
        if (!d.backdrop.isNullOrBlank()) b.backdrop.load(d.backdrop)
    }

    // ---------- seasons ----------

    private fun buildSeasons() {
        b.seasons.removeAllViews()
        val seasons = episodes.map { it.group }.distinct()
        // Start on the season of the episode being watched, else the first one.
        val current = store.entryFor(profile.id, "S:${item.id}")?.channel?.group
        season = if (current != null && current in seasons) current else seasons.firstOrNull()
        seasons.forEach { s ->
            val btn = layoutInflater.inflate(R.layout.item_season, b.seasons, false) as Button
            btn.text = "الموسم $s"
            btn.isActivated = s == season
            btn.setOnClickListener {
                season = s
                for (i in 0 until b.seasons.childCount) b.seasons.getChildAt(i).isActivated = b.seasons.getChildAt(i) == btn
                episodeAdapter.submit(visibleEpisodes())
            }
            b.seasons.addView(btn)
        }
        b.seasonsScroll.isVisible = seasons.size > 1
        episodeAdapter.submit(visibleEpisodes())
        b.emptyEpisodes.isVisible = episodes.isEmpty()
    }

    private fun visibleEpisodes(): List<Channel> = episodes.filter { season == null || it.group == season }

    // ---------- play / state ----------

    private fun currentEntry(): WatchEntry? =
        if (isSeries) store.entryFor(profile.id, "S:${item.id}") else store.progressFor(profile.id, item)

    private fun refreshState() {
        val fav = store.isFavorite(profile.id, favItem())
        b.btnFav.text = if (fav) "★ بالمفضلة" else "☆ أضف للمفضلة"

        val entry = currentEntry()
        if (entry != null && (entry.position > 0 || isSeries)) {
            val where = if (isSeries) entry.channel.name.substringBefore(" •") + " " else ""
            b.btnPlay.text = if (entry.position > 0) "▶ متابعة ${where}من ${formatTime(entry.position)}" else "▶ شغّل $where"
            b.btnRestart.isVisible = entry.position > 0
            b.progressBar.isVisible = entry.percent > 0
            b.progressBar.progress = entry.percent
        } else {
            b.btnPlay.text = if (isSeries) "▶ شغّل أول حلقة" else "▶ تشغيل"
            b.btnRestart.isVisible = false
            b.progressBar.isVisible = false
        }
        b.btnPlay.isEnabled = !isSeries || episodes.isNotEmpty()
        episodeAdapter.refresh()
    }

    private fun onPlay(fromStart: Boolean) {
        val start = if (fromStart) 0L else PlayerQueue.RESUME
        if (!isSeries) {
            Nav.play(this, profile, listOf(item), 0, start)
            return
        }
        val entry = currentEntry()
        val ep = entry?.let { e -> episodes.firstOrNull { it.url == e.channel.url } } ?: episodes.firstOrNull() ?: return
        playEpisode(ep, start)
    }

    /** Plays an episode with the whole series queued, so "next" and the episodes list work in the player. */
    private fun playEpisode(ep: Channel, start: Long) {
        val index = episodes.indexOfFirst { it.url == ep.url }
        if (index < 0) return
        Nav.play(this, profile, episodes, index, start)
    }
}
