package com.tvplayer.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * Main browsing screen for one server.
 *  - 🏠 Home: rows like big streaming apps (continue watching, favorites, newest…)
 *  - Live / Movies / Series: the server's own categories + content
 * Adapts to where it runs: side column of categories on TV / tablets,
 * chips on top on a phone held upright.
 */
class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PROFILE = "profile_id"
        const val CONTINUE_ID = "__cw__"
        const val FAV_ID = "__fav__"
        const val RECENT_ID = "__recent__"
        const val ALL_ID = "__all__"
    }

    private lateinit var b: ActivityBrowserBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: ServerProfile
    private var xtream: XtreamApi? = null
    private var section = Section.LIVE
    private var onHome = true
    private var narrow = false

    private val catAdapter = CategoryAdapter { onCategoryClick(it) }
    private lateinit var itemAdapter: ChannelAdapter
    private lateinit var homeAdapter: HomeAdapter

    /** url -> watched percent, refreshed whenever the screen comes back. */
    private var progress: Map<String, Int> = emptyMap()
    private var currentItems: List<Channel> = emptyList()
    private var currentCategory: Category? = null
    private var loadJob: Job? = null
    private var homeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ProfileStore(this)
        profile = store.get(intent.getStringExtra(EXTRA_PROFILE)) ?: run { finish(); return }
        Library.bind(profile.id)
        narrow = Device.isNarrow(this)

        itemAdapter = ChannelAdapter(
            onClick = { list, pos -> Nav.open(this, profile, list, pos) },
            onLongClick = { item -> Nav.itemOptions(this, store, profile, item) { refreshSpecial() } },
            progressOf = { progress[it.url] },
        )
        homeAdapter = HomeAdapter(
            onClick = { list, pos -> Nav.open(this, profile, list, pos) },
            onLongClick = { item -> Nav.itemOptions(this, store, profile, item) { refreshSpecial() } },
            progressOf = { progress[it.url] },
        )

        b.title.text = profile.name
        b.categories.adapter = catAdapter
        b.items.adapter = itemAdapter
        b.items.setHasFixedSize(true)
        b.items.setItemViewCacheSize(24)
        b.homeList.layoutManager = LinearLayoutManager(this)
        b.homeList.adapter = homeAdapter
        b.message.setOnClickListener { reload() }
        b.btnSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_PROFILE, profile.id))
        }
        b.btnRefresh.setOnClickListener { refreshFromServer() }
        applyLayoutForDevice()

        val isXtream = profile.type == ServerType.XTREAM
        if (isXtream) xtream = XtreamApi(profile)
        b.tabMovies.isVisible = isXtream
        b.tabSeries.isVisible = isXtream
        if (!isXtream) b.tabLive.text = "📺 كل المحتوى"
        b.tabHome.setOnClickListener { showHome() }
        b.tabLive.setOnClickListener { if (isXtream) selectSection(Section.LIVE) else showM3u() }
        b.tabMovies.setOnClickListener { selectSection(Section.MOVIES) }
        b.tabSeries.setOnClickListener { selectSection(Section.SERIES) }
        showHome()
        b.tabHome.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (::profile.isInitialized) refreshSpecial()
    }

    /** TV / tablet: categories in a side column. Phone upright: categories as chips on top. */
    private fun applyLayoutForDevice() {
        if (narrow) {
            b.root.setPadding(dp(12), dp(12), dp(12), 0)
            b.body.orientation = LinearLayout.VERTICAL
            b.categories.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            b.contentFrame.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
            b.categories.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            catAdapter.horizontal = true
            b.btnSearch.text = "🔍"
            b.title.textSize = 19f
        } else {
            b.categories.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- adult lock ----------

    private fun adultLocked() = store.lockAdult && !Pin.adultUnlocked

    /** Is this category / item behind the adult lock right now? */
    private fun hiddenCategory(c: Category) = adultLocked() && Pin.isAdult(c.name)

    private fun hiddenItem(c: Channel) = adultLocked() && Pin.isAdultItem(c, profile.type == ServerType.XTREAM)

    private fun visible(list: List<Channel>) = if (adultLocked()) list.filterNot { hiddenItem(it) } else list

    private fun displayCats(list: List<Category>) =
        list.map { if (hiddenCategory(it)) it.copy(name = "🔒 ${it.name}", count = -1) else it }

    // ---------- tabs ----------

    private fun setTab(home: Boolean, s: Section? = null) {
        onHome = home
        b.tabHome.isActivated = home
        b.tabLive.isActivated = !home && (s == Section.LIVE || s == null)
        b.tabMovies.isActivated = !home && s == Section.MOVIES
        b.tabSeries.isActivated = !home && s == Section.SERIES
        b.homeList.isVisible = home
        b.body.isVisible = !home
    }

    private fun refreshFromServer() {
        Http.clearCache()
        cacheDir.listFiles()?.filter { it.name == "playlist_${profile.id}.m3u" }?.forEach(File::delete)
        Library.clear()
        Library.bind(profile.id)
        Toast.makeText(this, "جاري التحديث من السيرفر…", Toast.LENGTH_SHORT).show()
        if (onHome) showHome() else if (profile.type == ServerType.XTREAM) selectSection(section) else showM3u()
    }

    // ---------- home ----------

    private fun showHome() {
        setTab(home = true)
        showLoading(false)
        progress = store.continueWatching(profile.id).associate { it.channel.url to it.percent }
        val xt = profile.type == ServerType.XTREAM
        val rows = ArrayList<HomeAdapter.Row>()
        val cw = visible(store.continueWatching(profile.id).map { e ->
            val c = e.channel
            if (c.kind == ItemKind.EPISODE && c.seriesName != null) c.copy(name = "${c.seriesName} • ${c.name.substringBefore(" •")}") else c
        })
        if (cw.isNotEmpty()) rows += HomeAdapter.Row("▶ متابعة المشاهدة", cw)
        val favs = visible(store.favorites(profile.id))
        favs.filter { it.kind != ItemKind.LIVE }.takeIf { it.isNotEmpty() }?.let { rows += HomeAdapter.Row("⭐ المفضلة", it) }
        favs.filter { it.kind == ItemKind.LIVE }.takeIf { it.isNotEmpty() }?.let { rows += HomeAdapter.Row("⭐ قنواتي المفضلة", it, tiles = true) }
        visible(store.recent(profile.id)).takeIf { it.isNotEmpty() }?.let { rows += HomeAdapter.Row("🕘 آخر القنوات", it, tiles = true) }
        homeAdapter.submit(rows.toList())

        homeJob?.cancel()
        homeJob = lifecycleScope.launch {
            try {
                if (xt) {
                    val api = xtream ?: return@launch
                    val movies = allOf(api, Section.MOVIES)
                    newest(movies)?.let { rows += HomeAdapter.Row("🆕 أحدث الأفلام", it) }
                    homeAdapter.submit(rows.toList())
                    val series = allOf(api, Section.SERIES)
                    newest(series)?.let { rows += HomeAdapter.Row("🆕 أحدث المسلسلات", it) }
                    homeAdapter.submit(rows.toList())
                    if (Library.categories[Section.LIVE] == null) {
                        Library.categories[Section.LIVE] = api.categories(Section.LIVE)
                    }
                } else {
                    val all = loadM3uList()
                    val firstGroup = all.firstOrNull()?.group
                    visible(all.filter { it.group == firstGroup }).take(40).takeIf { it.isNotEmpty() }?.let {
                        rows += HomeAdapter.Row("📺 $firstGroup", it, tiles = it.first().kind == ItemKind.LIVE)
                    }
                    homeAdapter.submit(rows.toList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rows.isEmpty()) {
                    rows += HomeAdapter.Row("تعذّر التحميل: ${e.message}", emptyList())
                    homeAdapter.submit(rows.toList())
                }
            }
            if (rows.isEmpty()) {
                homeAdapter.submit(listOf(HomeAdapter.Row("أهلاً! اختر البث المباشر أو الأفلام أو المسلسلات من فوق", emptyList())))
            }
        }
    }

    private suspend fun allOf(api: XtreamApi, s: Section): List<Channel> {
        if (Library.categories[s] == null) Library.categories[s] = api.categories(s)
        return Library.items["${s.name}:*"] ?: api.items(s, null).also { Library.items["${s.name}:*"] = it }
    }

    private fun newest(list: List<Channel>): List<Channel>? =
        visible(list).filter { it.added > 0 }.sortedByDescending { it.added }.take(30).takeIf { it.isNotEmpty() }

    // ---------- sections ----------

    private fun refreshSpecial() {
        progress = store.continueWatching(profile.id).associate { it.channel.url to it.percent }
        if (onHome) { showHome(); return }
        when (currentCategory?.id) {
            CONTINUE_ID, FAV_ID, RECENT_ID -> currentCategory?.let { onCategoryClick(it, keepScroll = true) }
            else -> itemAdapter.refresh()
        }
    }

    private fun reload() {
        if (profile.type == ServerType.XTREAM) {
            if (Library.categories[section] == null) selectSection(section)
            else currentCategory?.let { onCategoryClick(it) }
        } else showM3u()
    }

    private fun showLoading(on: Boolean) {
        b.progress.isVisible = on
        if (on) b.message.isVisible = false
    }

    private fun showMessage(text: String?) {
        b.message.text = text
        b.message.isVisible = text != null
    }

    private fun friendlyError(e: Exception) = "حصل خطأ: ${e.message ?: e.javaClass.simpleName}\n\nاضغط هنا لإعادة المحاولة"

    private suspend fun loadM3uList(): List<Channel> {
        if (Library.m3u.isNotEmpty()) return Library.m3u
        val cache = File(cacheDir, "playlist_${profile.id}.m3u")
        val text = try {
            Http.get(profile.url, profile.userAgent).also { t ->
                withContext(Dispatchers.IO) { runCatching { cache.writeText(t) } }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!cache.exists()) throw e
            Toast.makeText(this, "تعذّر التحديث، يتم عرض آخر نسخة محفوظة", Toast.LENGTH_LONG).show()
            withContext(Dispatchers.IO) { cache.readText() }
        }
        Library.m3u = withContext(Dispatchers.Default) { M3uParser.parse(text) }
        return Library.m3u
    }

    private fun showM3u() {
        setTab(home = false)
        setGrid(false)
        showLoading(true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val all = loadM3uList()
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
                catAdapter.submit(displayCats(cats))
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
        setTab(home = false, s = s)
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
                catAdapter.submit(displayCats(all))
                showLoading(false)
                // Open the first real server category that isn't locked (or "all").
                val firstOpen = cats.indexOfFirst { !hiddenCategory(it) }
                val first = if (firstOpen >= 0) special.size + firstOpen else special.size - 1
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
            val width = Device.widthDp(this) * (if (narrow) 1f else 0.7f)
            val tile = if (narrow) 115f else 140f
            GridLayoutManager(this, (width / tile).toInt().coerceAtLeast(2))
        } else LinearLayoutManager(this)
    }

    private fun focusCategory(position: Int) {
        b.categories.post {
            b.categories.scrollToPosition(position)
            if (Device.isTv(this)) {
                b.categories.post { b.categories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            }
        }
    }

    private fun onCategoryClick(cat: Category, keepScroll: Boolean = false) {
        // Locked (adult) category: ask for the PIN first.
        val cleanName = cat.name.removePrefix("🔒 ")
        if (adultLocked() && Pin.isAdult(cleanName)) {
            Pin.ask(this, "🔞 هذا القسم مقفل") {
                Pin.adultUnlocked = true
                catAdapter.submit(catAdapter.items.map { it.copy(name = it.name.removePrefix("🔒 ")) })
                onCategoryClick(cat.copy(name = cleanName))
            }
            return
        }
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
                showItems(list, "لسا ما بلّشت تحضر إشي", keepScroll = keepScroll)
                return
            }
            FAV_ID -> {
                val wanted = if (!xt) null else section.kind
                showItems(store.favorites(profile.id).filter { wanted == null || it.kind == wanted },
                    "ما في مفضلة لسا\nاضغط ضغطة مطوّلة على أي عنصر لإضافته", keepScroll = keepScroll)
                return
            }
            RECENT_ID -> {
                showItems(store.recent(profile.id), "ما حضرت أي قناة لسا", keepScroll = keepScroll)
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

    /** Items are always shown in the server's own order. */
    private fun showItems(
        list: List<Channel>,
        empty: String? = "لا يوجد محتوى هنا",

        keepScroll: Boolean = false,
    ) {
        val shown = visible(list)
        currentItems = shown
        itemAdapter.submit(shown)
        if (!keepScroll) b.items.scrollToPosition(0)
        if (!b.progress.isVisible) showMessage(if (shown.isEmpty()) empty else null)
    }
}
