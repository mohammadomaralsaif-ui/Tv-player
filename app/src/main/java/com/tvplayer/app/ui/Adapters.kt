package com.tvplayer.app.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.tvplayer.app.R
import com.tvplayer.app.data.Category
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType

@SuppressLint("NotifyDataSetChanged")
class ProfileAdapter(
    private val onClick: (ServerProfile) -> Unit,
    private val onLongClick: (ServerProfile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.VH>() {
    private var items: List<ServerProfile> = emptyList()

    fun submit(list: List<ServerProfile>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val icon: TextView = v.findViewById(R.id.icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        Focus.zoom(v, 1.02f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.name.text = p.name
        h.sub.text = when (p.type) {
            ServerType.XTREAM -> "Xtream Codes • ${p.username}"
            ServerType.M3U -> "قائمة M3U"
            ServerType.DIRECT -> "رابط بث مباشر"
        }
        h.icon.text = when (p.type) {
            ServerType.XTREAM -> "📡"
            ServerType.M3U -> "📋"
            ServerType.DIRECT -> "▶"
        }
        h.itemView.setOnClickListener { onClick(p) }
        h.itemView.setOnLongClickListener { onLongClick(p); true }
    }
}

@SuppressLint("NotifyDataSetChanged")
class CategoryAdapter(private val onClick: (Category) -> Unit) : RecyclerView.Adapter<CategoryAdapter.VH>() {
    var items: List<Category> = emptyList()
        private set

    /** Phone held upright: categories as a horizontal row of chips. */
    var horizontal = false
    var selectedId: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    fun submit(list: List<Category>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        if (horizontal) {
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (6 * parent.resources.displayMetrics.density).toInt()
            }
            v.findViewById<TextView>(R.id.name).layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            (v.findViewById<TextView>(R.id.name).layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight = 0f
        }
        Focus.zoom(v, 1.03f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.name
        h.count.text = if (c.count >= 0) c.count.toString() else ""
        h.itemView.isActivated = c.id == selectedId
        h.itemView.setOnClickListener { onClick(c) }
    }
}

/**
 * Channels / movies / series / episodes, as a list or as a poster grid.
 * [progressOf] returns watched percent (null = not started); [subtitleOf] an optional second line.
 */
@SuppressLint("NotifyDataSetChanged")
class ChannelAdapter(
    private val onClick: (List<Channel>, Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
    var progressOf: (Channel) -> Int? = { null },
    var subtitleOf: (Channel) -> String? = { defaultSubtitle(it) },
    private val showNumbers: Boolean = true,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    /** Small text at the end of a list row (e.g. the next program). */
    var extraOf: (Channel) -> String? = { null }

    /** Called when a row gets focus (TV): drives the live info panel. */
    var onFocusItem: ((Channel) -> Unit)? = null

    /** Wide 16:9 cards with a title + second line (continue watching). */
    var landscape = false

    /** Episode rows: 16:9 thumbnail, title, length / progress, play button. */
    var episodeRows = false

    var items: List<Channel> = emptyList()
        private set

    /** Index drawn as "now playing" (player side panel). */
    var highlighted: Int = -1
        set(v) { field = v; notifyDataSetChanged() }

    /** Width of each tile in a horizontal home row (0 = fill the grid cell). */
    var fixedWidthPx = 0

    /** Wide tiles for channel logos (instead of tall posters). */
    var tiles = false

    /** Grid of posters (movies / series) instead of a channel list. */
    var grid = false
        set(v) { if (field != v) { field = v; notifyDataSetChanged() } }

    fun submit(list: List<Channel>) { items = list; notifyDataSetChanged() }
    fun refresh() = notifyDataSetChanged()

    /** Re-binds visible rows without recreating them, so TV focus stays where it is. */
    fun refreshQuiet() = notifyItemRangeChanged(0, itemCount, "quiet")

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val number: TextView? = v.findViewById(R.id.number)
        val badge: TextView? = v.findViewById(R.id.badge)
        val extra: TextView? = v.findViewById(R.id.extra)
    }

    override fun getItemViewType(position: Int) = when {
        episodeRows -> 3
        landscape -> 2
        grid -> 1
        else -> 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            1 -> R.layout.item_poster
            2 -> R.layout.item_landscape
            3 -> R.layout.item_episode
            else -> R.layout.item_channel
        }
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.findViewById<ImageView>(R.id.logo).clipToOutline = true // rounded corners on posters
        if ((viewType == 1 || viewType == 2) && fixedWidthPx > 0) {
            v.layoutParams = RecyclerView.LayoutParams(fixedWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (viewType == 1 && tiles) {
            val logo = v.findViewById<ImageView>(R.id.logo)
            logo.layoutParams.height = ((if (fixedWidthPx > 0) fixedWidthPx else (130 * parent.resources.displayMetrics.density).toInt()) * 0.62f).toInt()
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (10 * parent.resources.displayMetrics.density).toInt()
            logo.setPadding(pad, pad, pad, pad)
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val type = getItemViewType(position)
        h.name.text = when {
            position == highlighted -> "▶ ${c.name}"
            type == 2 -> c.seriesName ?: c.name
            else -> c.name
        }
        h.extra?.let { e ->
            val x = extraOf(c)
            e.text = x
            e.visibility = if (x.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            Focus.animate(v, has, if (type == 0) 1.02f else 1.07f)
            if (has) onFocusItem?.invoke(c)
        }
        h.number?.text = (position + 1).toString()
        h.number?.visibility = if (showNumbers) View.VISIBLE else View.GONE

        val sub = if (type == 1 && c.rating.isNotBlank()) c.year.ifBlank { null } else subtitleOf(c)
        h.sub.text = sub
        h.sub.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE

        // Poster: rating badge. List row: red LIVE tag on live channels.
        h.badge?.let { badge ->
            if (type == 1 || type == 2) {
                badge.text = if (c.rating.isNotBlank()) "★ ${c.rating}" else ""
                badge.visibility = if (c.rating.isNotBlank()) View.VISIBLE else View.GONE
            } else {
                badge.visibility = if (c.kind == ItemKind.LIVE) View.VISIBLE else View.GONE
            }
        }

        val pct = progressOf(c)
        h.progress.visibility = if (pct != null && pct > 0) View.VISIBLE else View.GONE
        h.progress.progress = pct ?: 0

        if (c.logo.isNullOrBlank()) {
            h.logo.dispose()
            h.logo.setImageResource(R.drawable.ic_tv)
        } else {
            h.logo.load(c.logo) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(items, p)
        }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    companion object {
        fun defaultSubtitle(c: Channel): String? {
            val parts = ArrayList<String>()
            if (c.rating.isNotBlank()) parts += "★ ${c.rating}"
            if (c.year.isNotBlank()) parts += c.year
            return parts.joinToString("  •  ").ifBlank { null }
        }

        fun kindLabel(c: Channel): String = when (c.kind) {
            ItemKind.LIVE -> "📺 مباشر"
            ItemKind.MOVIE -> "🎬 فيلم"
            ItemKind.SERIES -> "🎞 مسلسل"
            ItemKind.EPISODE -> "🎞 حلقة"
        }
    }
}


/** On TV, the focused card grows a little so it's easy to see from the sofa. */
object Focus {
    fun zoom(v: View, scale: Float) {
        if (!Device.isTv(v.context)) return
        v.setOnFocusChangeListener { view, has -> animate(view, has, scale) }
    }

    fun animate(v: View, has: Boolean, scale: Float) {
        if (!Device.isTv(v.context)) return
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
            .translationZ(if (has) 8f else 0f).setDuration(140).start()
    }
}

/** Home screen: vertical list of titled, horizontally scrolling rows. */
@SuppressLint("NotifyDataSetChanged")
class HomeAdapter(
    private val onClick: (List<Channel>, Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
    private val progressOf: (Channel) -> Int?,
) : RecyclerView.Adapter<HomeAdapter.VH>() {

    /** [hero] = the big featured card at the top (uses the first item). */
    class Row(
        val title: String,
        val items: List<Channel>,
        val tiles: Boolean = false,
        val hero: Boolean = false,
        val subtitle: String? = null,
        /** Wide 16:9 cards (continue watching). */
        val landscape: Boolean = false,
        /** Second line per item, keyed by url (e.g. "S2 E1 • باقي 9 د"). */
        val subs: Map<String, String> = emptyMap(),
    )

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }

    open class VH(v: View) : RecyclerView.ViewHolder(v)
    class RowVH(v: View, val title: TextView, val list: RecyclerView, val adapter: ChannelAdapter) : VH(v)
    class HeroVH(v: View) : VH(v) {
        val backdrop: ImageView = v.findViewById(R.id.heroBackdrop)
        val poster: ImageView = v.findViewById(R.id.heroPoster)
        val label: TextView = v.findViewById(R.id.heroLabel)
        val title: TextView = v.findViewById(R.id.heroTitle)
        val sub: TextView = v.findViewById(R.id.heroSub)
        val progress: ProgressBar = v.findViewById(R.id.heroProgress)
        val play: android.widget.Button = v.findViewById(R.id.heroPlay)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        if (viewType == 2) {
            val v = LayoutInflater.from(ctx).inflate(R.layout.item_hero, parent, false)
            v.findViewById<ImageView>(R.id.heroPoster).clipToOutline = true
            v.clipToOutline = true
            val dd = ctx.resources.displayMetrics.density
            if (Device.isNarrow(ctx)) {
                v.layoutParams.height = (220 * dd).toInt()
                v.findViewById<ImageView>(R.id.heroPoster).visibility = View.GONE
            } else {
                v.layoutParams.height = (330 * dd).toInt()
                v.findViewById<ImageView>(R.id.heroPoster).layoutParams.apply { width = (180 * dd).toInt(); height = (270 * dd).toInt() }
                v.findViewById<TextView>(R.id.heroTitle).textSize = 40f
            }
            return HeroVH(v)
        }
        val d = ctx.resources.displayMetrics.density
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * d).toInt(), 0, (10 * d).toInt())
        }
        val title = TextView(ctx).apply {
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((4 * d).toInt(), 0, 0, (8 * d).toInt())
        }
        val adapter = ChannelAdapter(onClick = onClick, onLongClick = onLongClick, progressOf = progressOf).apply {
            grid = true
            tiles = viewType == 1
            landscape = viewType == 3
            fixedWidthPx = (when {
                viewType == 3 -> if (Device.isNarrow(ctx)) 220 else 290
                Device.isNarrow(ctx) -> 118
                else -> 140
            } * d).toInt()
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            clipToPadding = false
            setPadding((2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt())
        }
        box.addView(title)
        box.addView(list, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return RowVH(box, title, list, adapter)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        if (holder is HeroVH) {
            val item = row.items.firstOrNull() ?: return
            holder.label.text = row.title
            holder.title.text = item.seriesName ?: item.name
            holder.sub.text = row.subtitle ?: ChannelAdapter.defaultSubtitle(item) ?: ""
            val pct = progressOf(item)
            holder.progress.visibility = if (pct != null && pct > 0) View.VISIBLE else View.GONE
            holder.progress.progress = pct ?: 0
            holder.backdrop.load(item.logo)
            holder.poster.load(item.logo) { placeholder(R.drawable.ic_tv); error(R.drawable.ic_tv) }
            holder.play.text = if (pct != null && pct > 0) "▶  كمّل المشاهدة" else "▶  شاهد الآن"
            holder.play.setOnClickListener { onClick(row.items, 0) }
            holder.itemView.setOnClickListener { onClick(row.items, 0) }
            return
        }
        val h = holder as RowVH
        h.title.text = row.title
        h.adapter.subtitleOf = if (row.subs.isNotEmpty()) {
            { c -> row.subs[c.url] ?: ChannelAdapter.defaultSubtitle(c) }
        } else {
            { c -> ChannelAdapter.defaultSubtitle(c) }
        }
        h.adapter.submit(row.items)
        h.list.isVisible = row.items.isNotEmpty()
        h.list.scrollToPosition(0)
    }

    override fun getItemViewType(position: Int) = when {
        rows[position].hero -> 2
        rows[position].landscape -> 3
        rows[position].tiles -> 1
        else -> 0
    }
}
