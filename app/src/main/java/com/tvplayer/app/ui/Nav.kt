package com.tvplayer.app.ui

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import com.tvplayer.app.data.Channel
import com.tvplayer.app.data.ItemKind
import com.tvplayer.app.data.PlayerQueue
import com.tvplayer.app.data.ProfileStore
import com.tvplayer.app.data.ServerProfile
import com.tvplayer.app.data.ServerType

/** Decides what opening an item means: play it, or show its details page. */
object Nav {

    fun play(ctx: Context, profile: ServerProfile, list: List<Channel>, pos: Int, start: Long = PlayerQueue.RESUME) {
        PlayerQueue.set(list, pos, profile, start)
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }

    fun open(ctx: Context, profile: ServerProfile, list: List<Channel>, pos: Int) {
        val item = list[pos]
        val xtream = profile.type == ServerType.XTREAM
        val seriesId = item.seriesId
        when {
            item.kind == ItemKind.SERIES -> details(ctx, profile, item, null)
            item.kind == ItemKind.MOVIE && xtream -> details(ctx, profile, item, null)
            item.kind == ItemKind.EPISODE && xtream && !seriesId.isNullOrEmpty() -> {
                // From "continue watching": open the series page and jump straight into this episode.
                val series = Channel(
                    id = seriesId, name = item.seriesName ?: "", logo = item.logo,
                    group = "", url = "", kind = ItemKind.SERIES,
                )
                details(ctx, profile, series, item.id)
            }
            else -> {
                val playable = list.filter { it.kind != ItemKind.SERIES }
                play(ctx, profile, playable, playable.indexOf(item).coerceAtLeast(0))
            }
        }
    }

    fun details(ctx: Context, profile: ServerProfile, item: Channel, autoplayEpisodeId: String?) {
        ctx.startActivity(
            Intent(ctx, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_PROFILE, profile.id)
                .putExtra(DetailsActivity.EXTRA_ITEM, item.toJson().toString())
                .putExtra(DetailsActivity.EXTRA_AUTOPLAY, autoplayEpisodeId)
        )
    }

    /**
     * Long-press menu: favorites, and removing from "continue watching" / "recent channels".
     * [onChanged] is called after anything changes so the screen can refresh.
     */
    fun itemOptions(ctx: Context, store: ProfileStore, profile: ServerProfile, item: Channel, onChanged: () -> Unit) {
        val fav = store.isFavorite(profile.id, item)
        val inContinue = store.entryFor(profile.id, item.watchKey) != null
        val inRecent = item.kind == ItemKind.LIVE && store.recent(profile.id).any { it.favKey == item.favKey }

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels += if (fav) "★ إزالة من المفضلة" else "☆ إضافة للمفضلة"
        actions += {
            val added = store.toggleFavorite(profile.id, item)
            Toast.makeText(ctx, if (added) "انضافت للمفضلة ⭐" else "انشالت من المفضلة", Toast.LENGTH_SHORT).show()
        }
        if (inContinue) {
            labels += "✕ إزالة من متابعة المشاهدة"
            actions += { store.removeProgress(profile.id, item.watchKey) }
        }
        if (inRecent) {
            labels += "✕ إزالة من آخر القنوات"
            actions += { store.removeRecent(profile.id, item) }
        }

        AlertDialog.Builder(ctx)
            .setTitle(item.seriesName ?: item.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which](); onChanged() }
            .show()
    }
}
