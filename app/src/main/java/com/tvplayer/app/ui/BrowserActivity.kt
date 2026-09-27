package com.tvplayer.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvplayer.app.data.Category
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
import com.tvplayer.app.databinding.ActivityBrowserBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two-pane browser: the server's own categories on one side, content on the other.
 * Special categories on top: continue watching, favorites, recent channels, all.
 */
class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        const val CONTINUE_ID = "__cw__"
        const val FAV_ID = "__fav__"
        const val RECENT_ID = "__recent__"
        const val ALL_ID = "__all__"
        val SORT_NAMES = arrayOf("ترتيب السيرفر", "الأحدث إضافة", "الاسم (أ-ي)", "الأعلى تقييماً", "الأحدث سنةً")
    }

    private lateinit var b: ActivityBrowserBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: ServerProfile
    private var xtream: XtreamApi? = null
    private var section = Section.LIVE

    private val catAdapter = CategoryAdapter { onCategoryClick(it) }
    private lateinit var itemAdapter: ChannelAdapter

    /** url -> watched percent, refreshed whenever the screen comes back. */
    private var progress: Map<String, Int> = emptyMap()
    private var currentItems: List<Channel> = emptyList()
    private var currentCategory: Category? = null
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        profile = store.get(intent.getStringExtra(EXTRA_PROFILE)) ?: run { finish(); return }
        Library.bind(profile.id)

        itemAdapter = ChannelAdapter(
            onClick = { list, pos -> Nav.open(this, profile, list, pos) },
            onLongClick = { item -> Nav.itemOptions(this, store, profile, item) { refreshSpecial() } },
            progressOf = { progress[it.url] },
        )

        b.title.text = profile.name
        b.categories.layoutManager = LinearLayoutManager(this)
        b.categories.adapter = catAdapter
        b.items.layoutManager = LinearLayoutManager(this)
        b.items.adapter = itemAdapter
        b.message.setOnClickListener { reload() }
        b.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_PROFILE, profile.id))
        }
        b.btnSort.text = "⇅ ${SORT_NAMES[store.sortMode]}"
        b.btnSort.setOnClickListener { chooseSort() }

        when (profile.type) {
            ServerType.XTREAM -> {
                xtream = XtreamApi(profile)
                b.tabs.isVisible = true
                b.tabLive.setOnClickListener { selectSection(Section.LIVE) }
                b.tabMovies.setOnClickListener { selectSection(Section.MOVIES) }
                b.tabSeries.setOnClickListener { selectSection(Section.SERIES) }
                selectSection(Section.LIVE)
            }
            else -> loadM3u()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::profile.isInitialized) refreshSpecial()
    }

    /** Re-reads progress and re-shows the list if a user-managed category is open. */
    private fun refreshSpecial() {
        progress = store.continueWatching(profile.id).associate { it.channel.url to it.percent }
        when (currentCategory?.id) {
            CONTINUE_ID, FAV_ID, RECENT_ID -> currentCategory?.let { onCategoryClick(it, keepScroll = true) }
            else -> itemAdapter.refresh()
        }
    }

    private fun reload() {
        if (profile.type == ServerType.XTREAM) {
            if (Library.categories[section] == null) selectSection(section)
            else currentCategory?.let { onCategoryClick(it) }
        } else if (Library.m3u.isEmpty()) loadM3u()
    }

    private fun chooseSort() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("الترتيب")
            .setSingleChoiceItems(SORT_NAMES, store.sortMode) { d, which ->
                store.sortMode = which
                b.btnSort.text = "⇅ ${SORT_NAMES[which]}"
                applySort()
                d.dismiss()
            }
            .show()
    }

    // ---------- loading ----------

    private fun showLoading(on: Boolean) {
        b.progress.isVisible = on
        if (on) b.message.isVisible = false
    }

    private fun showMessage(text: String?) {
        b.message.text = text
        b.message.isVisible = text != null
    }

    private fun friendlyError(e: Exception) = "حصل خطأ: ${e.message ?: e.javaClass.simpleName}\n\nاضغط هنا لإعادة المحاولة"

    private fun loadM3u() {
        showLoading(true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                if (Library.m3u.isEmpty()) {
                    val cache = File(cacheDir, "playlist_${profile.id}.m3u")
                    val text = try {
                        Http.get(profile.url, profile.userAgent).also { t ->
                            withContext(Dispatchers.IO) { runCatching { cache.writeText(t) } }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (!cache.exists()) throw e
                        Toast.makeText(this@BrowserActivity, "تعذّر التحديث، يتم عرض آخر نسخة محفوظة", Toast.LENGTH_LONG).show()
                        withContext(Dispatchers.IO) { cache.readText() }
                    }
                    Library.m3u = withContext(Dispatchers.Default) { M3uParser.parse(text) }
                }
                val all = Library.m3u
                if (all.isEmpty()) {
                    showLoading(false); showMessage("القائمة فاضية أو الرابط مش قائمة M3U"); return@launch
                }
                val groups = LinkedHashMap<String, Int>()
                all.forEach { groups[it.group] = (groups[it.group] ?: 0) + 1 }
                val cats = listOf(
                    Category(CONTINUE_ID, "▶ متابعة المشاهدة"),
                    Category(FAV_ID, "⭐ المفضلة"),
                    Category(RECENT_ID, "🕘 آخر القنوات"),
                    Category(ALL_ID, "الكل", all.size),
                ) + groups.map { (g, n) -> Category(g, g, n) }
                catAdapter.submit(cats)
                showLoading(false)
                onCategoryClick(cats[3])
                focusCategory(3)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoading(false); showMessage(friendlyError(e))
            }
        }
    }

    private fun selectSection(s: Section) {
        val api = xtream ?: return
        section = s
        b.tabLive.isActivated = s == Section.LIVE
        b.tabMovies.isActivated = s == Section.MOVIES
        b.tabSeries.isActivated = s == Section.SERIES
        setGrid(s != Section.LIVE)
        itemAdapter.submit(emptyList())
        catAdapter.submit(emptyList())
        currentCategory = null
        showLoading(true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val cats = Library.categories[s] ?: api.categories(s).also { Library.categories[s] = it }
                val special = if (s == Section.LIVE) {
                    listOf(Category(FAV_ID, "⭐ المفضلة"), Category(RECENT_ID, "🕘 آخر القنوات"), Category(ALL_ID, "الكل"))
                } else {
                    listOf(Category(CONTINUE_ID, "▶ متابعة المشاهدة"), Category(FAV_ID, "⭐ المفضلة"), Category(ALL_ID, "الكل"))
                }
                val all = special + cats
                catAdapter.submit(all)
                showLoading(false)
                // Open the first real server category (or "all" if the server has none).
                val first = if (cats.isNotEmpty()) special.size else special.size - 1
                onCategoryClick(all[first])
                focusCategory(first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoading(false); showMessage(friendlyError(e))
            }
        }
    }

    private fun setGrid(grid: Boolean) {
        itemAdapter.grid = grid
        b.items.layoutManager = if (grid) {
            val dm = resources.displayMetrics
            val widthDp = dm.widthPixels / dm.density * 0.7f
            GridLayoutManager(this, (widthDp / 140f).toInt().coerceAtLeast(2))
        } else LinearLayoutManager(this)
    }

    private fun focusCategory(position: Int) {
        b.categories.post {
            b.categories.scrollToPosition(position)
            b.categories.post { b.categories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
        }
    }

    private fun onCategoryClick(cat: Category, keepScroll: Boolean = false) {
        currentCategory = cat
        catAdapter.selectedId = cat.id
        showMessage(null)
        val xt = profile.type == ServerType.XTREAM

        when (cat.id) {
            CONTINUE_ID -> {
                val wanted = if (!xt) null else if (section == Section.SERIES) ItemKind.EPISODE else ItemKind.MOVIE
                val list = store.continueWatching(profile.id)
                    .map { it.channel }
                    .filter { wanted == null || it.kind == wanted }
                    .map { c -> if (c.kind == ItemKind.EPISODE && c.seriesName != null) c.copy(name = "${c.seriesName} — ${c.name}") else c }
                showItems(list, "لسا ما بلّشت تحضر إشي", sort = false, keepScroll = keepScroll)
                return
            }
            FAV_ID -> {
                val wanted = if (!xt) null else section.kind
                showItems(store.favorites(profile.id).filter { wanted == null || it.kind == wanted },
                    "ما في مفضلة لسا\nاضغط ضغطة مطوّلة على أي عنصر لإضافته", sort = false, keepScroll = keepScroll)
                return
            }
            RECENT_ID -> {
                showItems(store.recent(profile.id), "ما حضرت أي قناة لسا", sort = false, keepScroll = keepScroll)
                return
            }
        }

        if (!xt) {
            showItems(if (cat.id == ALL_ID) Library.m3u else Library.m3u.filter { it.group == cat.id })
            return
        }

        val api = xtream ?: return
        val catId = if (cat.id == ALL_ID) null else cat.id
        val key = "${section.name}:${catId ?: "*"}"
        Library.items[key]?.let { showItems(it); return }
        val s = section
        showItems(emptyList(), null)
        showLoading(true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val list = api.items(s, catId)
                Library.items[key] = list
                showLoading(false)
                if (currentCategory == cat && section == s) showItems(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoading(false); showMessage(friendlyError(e))
            }
        }
    }

    private var sortCurrent = true

    private fun showItems(
        list: List<Channel>,
        empty: String? = "لا يوجد محتوى هنا",
        sort: Boolean = true,
        keepScroll: Boolean = false,
    ) {
        currentItems = list
        sortCurrent = sort
        itemAdapter.submit(if (sort) sorted(list) else list)
        if (!keepScroll) b.items.scrollToPosition(0)
        if (!b.progress.isVisible) showMessage(if (list.isEmpty()) empty else null)
    }

    private fun applySort() {
        itemAdapter.submit(if (sortCurrent) sorted(currentItems) else currentItems)
        b.items.scrollToPosition(0)
    }

    private fun sorted(list: List<Channel>): List<Channel> = when (store.sortMode) {
        1 -> list.sortedByDescending { it.added }
        2 -> list.sortedBy { it.name.lowercase() }
        3 -> list.sortedByDescending { it.rating.toDoubleOrNull() ?: -1.0 }
        4 -> list.sortedByDescending { it.year.take(4).toIntOrNull() ?: 0 }
        else -> list
    }
}
