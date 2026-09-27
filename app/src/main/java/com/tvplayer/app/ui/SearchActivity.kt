package com.tvplayer.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.Http
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.Library
import com.tvplayer.app.data.M3uParser
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType
import com.tvplayer.app.data.XtreamApi
import com.tvplayer.app.data.XtreamApi.Section
import com.tvplayer.app.databinding.ActivitySearchBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One search box over everything on the server: live channels, movies and series. */
class SearchActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        private const val MAX_RESULTS = 400
    }

    private lateinit var b: ActivitySearchBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: ServerProfile
    private lateinit var adapter: ChannelAdapter

    private var everything: List<Channel> = emptyList()
    private var loaded = false
    /** null = all kinds */
    private var filter: ItemKind? = null
    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        profile = store.get(intent.getStringExtra(EXTRA_PROFILE)) ?: run { finish(); return }
        Library.bind(profile.id)

        adapter = ChannelAdapter(
            onClick = { list, pos -> Nav.open(this, profile, list, pos) },
            onLongClick = { item -> Nav.itemOptions(this, store, profile, item) {} },
            subtitleOf = { c ->
                listOfNotNull(ChannelAdapter.kindLabel(c), ChannelAdapter.defaultSubtitle(c)).joinToString("  •  ")
            },
            showNumbers = false,
        )
        b.items.layoutManager = LinearLayoutManager(this)
        b.items.adapter = adapter

        b.query.doAfterTextChanged {
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, 300)
        }
        val filters = listOf(b.fAll to null, b.fLive to ItemKind.LIVE, b.fMovies to ItemKind.MOVIE, b.fSeries to ItemKind.SERIES)
        filters.forEach { (btn, kind) ->
            btn.setOnClickListener {
                filter = kind
                filters.forEach { (o, k) -> o.isActivated = k == kind }
                runSearch()
            }
        }
        b.fAll.isActivated = true

        b.query.requestFocus()
        b.query.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.query, 0)
        }
        loadEverything()
    }

    private fun loadEverything() {
        b.status.isVisible = true
        b.status.text = "جاري تحميل مكتبة السيرفر للبحث…"
        lifecycleScope.launch {
            try {
                everything = if (profile.type == ServerType.XTREAM) {
                    val api = XtreamApi(profile)
                    suspend fun all(s: Section): List<Channel> =
                        Library.items["${s.name}:*"] ?: api.items(s, null).also { Library.items["${s.name}:*"] = it }
                    // Load the three sections in parallel; a failing one doesn't block the others.
                    val live = async { runCatching { all(Section.LIVE) }.getOrDefault(emptyList()) }
                    val movies = async { runCatching { all(Section.MOVIES) }.getOrDefault(emptyList()) }
                    val series = async { runCatching { all(Section.SERIES) }.getOrDefault(emptyList()) }
                    live.await() + movies.await() + series.await()
                } else {
                    if (Library.m3u.isEmpty()) {
                        val text = Http.get(profile.url, profile.userAgent)
                        Library.m3u = withContext(Dispatchers.Default) { M3uParser.parse(text) }
                    }
                    Library.m3u
                }
                loaded = true
                b.status.text = "جاهز — ${everything.size} عنصر"
                runSearch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.status.text = "تعذّر تحميل المكتبة: ${e.message}"
            }
        }
    }

    private fun runSearch() {
        val q = b.query.text.toString().trim()
        if (q.length < 2) {
            adapter.submit(emptyList())
            b.message.isVisible = loaded
            b.message.text = "اكتب حرفين أو أكثر"
            return
        }
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) {
                everything.asSequence()
                    .filter { filter == null || it.kind == filter }
                    .filter { c -> val n = c.name.lowercase(); words.all { n.contains(it) } }
                    // names that start with the query first, then live → movies → series
                    .sortedWith(compareBy({ !it.name.lowercase().startsWith(words.first()) }, { it.kind.ordinal }))
                    .take(MAX_RESULTS)
                    .toList()
            }
            adapter.submit(results)
            b.message.isVisible = results.isEmpty() && loaded
            b.message.text = "ما في نتائج لـ \"$q\""
            b.status.visibility = if (loaded) View.GONE else View.VISIBLE
        }
    }
}
