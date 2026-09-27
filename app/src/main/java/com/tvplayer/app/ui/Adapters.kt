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
            ServerType.XTREAM -> "📡  Xtream Codes • ${p.username}"
            ServerType.M3U -> "📋  قائمة M3U"
            ServerType.DIRECT -> "🔗  رابط بث مباشر"
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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val number: TextView? = v.findViewById(R.id.number)
    }

    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_poster else R.layout.item_channel
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.findViewById<ImageView>(R.id.logo).clipToOutline = true // rounded corners on posters
        if (viewType == 1 && fixedWidthPx > 0) {
            v.layoutParams = RecyclerView.LayoutParams(fixedWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (viewType == 1 && tiles) {
            val logo = v.findViewById<ImageView>(R.id.logo)
            logo.layoutParams.height = ((if (fixedWidthPx > 0) fixedWidthPx else (130 * parent.resources.displayMetrics.density).toInt()) * 0.62f).toInt()
            logo.scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (10 * parent.resources.displayMetrics.density).toInt()
            logo.setPadding(pad, pad, pad, pad)
        }
        Focus.zoom(v, if (viewType == 1) 1.07f else 1.02f)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = if (position == highlighted) "▶ ${c.name}" else c.name
        h.number?.text = (position + 1).toString()
        h.number?.visibility = if (showNumbers) View.VISIBLE else View.GONE

        val sub = subtitleOf(c)
        h.sub.text = sub
        h.sub.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE

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
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f)
                .translationZ(if (has) 8f else 0f).setDuration(140).start()
        }
    }
}

/** Home screen: vertical list of titled, horizontally scrolling rows. */
@SuppressLint("NotifyDataSetChanged")
class HomeAdapter(
    private val onClick: (List<Channel>, Int) -> Unit,
    private val onLongClick: (Channel) -> Unit,
    private val progressOf: (Channel) -> Int?,
) : RecyclerView.Adapter<HomeAdapter.VH>() {

    class Row(val title: String, val items: List<Channel>, val tiles: Boolean = false)

    private var rows: List<Row> = emptyList()

    fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }

    class VH(v: View, val title: TextView, val list: RecyclerView, val adapter: ChannelAdapter) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
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
            fixedWidthPx = ((if (Device.isNarrow(ctx)) 118 else 140) * d).toInt()
        }
        val list = RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
            clipToPadding = false
            setPadding((2 * d).toInt(), (4 * d).toInt(), (2 * d).toInt(), (4 * d).toInt())
        }
        box.addView(title)
        box.addView(list, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return VH(box, title, list, adapter)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val row = rows[position]
        h.title.text = row.title
        h.adapter.submit(row.items)
        h.list.isVisible = row.items.isNotEmpty()
        h.list.scrollToPosition(0)
    }

    override fun getItemViewType(position: Int) = if (rows[position].tiles) 1 else 0
}
