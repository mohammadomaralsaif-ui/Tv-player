package com.tvplayer.app.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.name.text = p.name
        h.sub.text = when (p.type) {
            ServerType.XTREAM -> "Xtream Codes • ${p.username}"
            ServerType.M3U -> "قائمة M3U"
            ServerType.DIRECT -> "رابط بث مباشر"
        }
        h.itemView.setOnClickListener { onClick(p) }
        h.itemView.setOnLongClickListener { onLongClick(p); true }
    }
}

@SuppressLint("NotifyDataSetChanged")
class CategoryAdapter(private val onClick: (Category) -> Unit) : RecyclerView.Adapter<CategoryAdapter.VH>() {
    var items: List<Category> = emptyList()
        private set
    var selectedId: String? = null
        set(v) { field = v; notifyDataSetChanged() }

    fun submit(list: List<Category>) { items = list; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

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
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
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
