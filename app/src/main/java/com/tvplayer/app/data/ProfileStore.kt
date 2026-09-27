package com.tvplayer.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Saved servers, favorites, continue-watching, recent channels and player settings. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tvplayer", Context.MODE_PRIVATE)

    // ---------- helpers ----------

    private fun readArray(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }

    private fun objects(key: String): List<JSONObject> {
        val arr = readArray(key)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun writeObjects(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    // ---------- servers ----------

    fun all(): List<ServerProfile> = objects("profiles").map { ServerProfile.fromJson(it) }

    fun get(id: String?): ServerProfile? = all().firstOrNull { it.id == id }

    fun save(profile: ServerProfile) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list[i] = profile else list.add(profile)
        writeObjects("profiles", list.map { it.toJson() })
    }

    fun delete(id: String) {
        writeObjects("profiles", all().filter { it.id != id }.map { it.toJson() })
        prefs.edit().remove("fav_$id").remove("cw_$id").remove("recent_$id").apply()
    }

    // ---------- favorites ----------

    fun favorites(profileId: String): List<Channel> = objects("fav_$profileId").map { Channel.fromJson(it) }

    fun isFavorite(profileId: String, channel: Channel): Boolean =
        favorites(profileId).any { it.favKey == channel.favKey }

    /** Returns true if the item was added, false if it was removed. */
    fun toggleFavorite(profileId: String, channel: Channel): Boolean {
        val favs = favorites(profileId).toMutableList()
        val i = favs.indexOfFirst { it.favKey == channel.favKey }
        val added = if (i >= 0) {
            favs.removeAt(i); false
        } else {
            favs.add(0, channel); true
        }
        writeObjects("fav_$profileId", favs.map { it.toJson() })
        return added
    }

    // ---------- continue watching ----------

    fun continueWatching(profileId: String): List<WatchEntry> =
        objects("cw_$profileId").map {
            WatchEntry(
                channel = Channel.fromJson(it.optJSONObject("ch") ?: JSONObject()),
                position = it.optLong("pos"),
                duration = it.optLong("dur"),
                updated = it.optLong("t"),
            )
        }.sortedByDescending { it.updated }

    fun saveProgress(profileId: String, channel: Channel, position: Long, duration: Long) {
        val list = continueWatching(profileId).filter { it.channel.watchKey != channel.watchKey }.toMutableList()
        list.add(0, WatchEntry(channel, position, duration, System.currentTimeMillis()))
        writeObjects("cw_$profileId", list.take(60).map {
            JSONObject().apply {
                put("ch", it.channel.toJson())
                put("pos", it.position)
                put("dur", it.duration)
                put("t", it.updated)
            }
        })
    }

    /** Saved progress for exactly this item (same movie / same episode). */
    fun progressFor(profileId: String, channel: Channel): WatchEntry? =
        continueWatching(profileId).firstOrNull { it.channel.watchKey == channel.watchKey && it.channel.url == channel.url }

    fun entryFor(profileId: String, watchKey: String): WatchEntry? =
        continueWatching(profileId).firstOrNull { it.channel.watchKey == watchKey }

    fun removeProgress(profileId: String, watchKey: String) {
        val list = continueWatching(profileId).filter { it.channel.watchKey != watchKey }
        writeObjects("cw_$profileId", list.map {
            JSONObject().apply {
                put("ch", it.channel.toJson())
                put("pos", it.position)
                put("dur", it.duration)
                put("t", it.updated)
            }
        })
    }

    fun clearContinueWatching(profileId: String) {
        prefs.edit().remove("cw_$profileId").apply()
    }

    // ---------- recently watched live channels ----------

    fun recent(profileId: String): List<Channel> = objects("recent_$profileId").map { Channel.fromJson(it) }

    fun addRecent(profileId: String, channel: Channel) {
        val list = recent(profileId).filter { it.favKey != channel.favKey }.toMutableList()
        list.add(0, channel)
        writeObjects("recent_$profileId", list.take(30).map { it.toJson() })
    }

    fun removeRecent(profileId: String, channel: Channel) {
        writeObjects("recent_$profileId", recent(profileId).filter { it.favKey != channel.favKey }.map { it.toJson() })
    }

    // ---------- settings ----------

    /** Max video height (0 = automatic / best). */
    var maxQuality: Int
        get() = prefs.getInt("max_quality", 0)
        set(v) = prefs.edit().putInt("max_quality", v).apply()

    var subtitleScale: Float
        get() = prefs.getFloat("sub_scale", 1f)
        set(v) = prefs.edit().putFloat("sub_scale", v).apply()

    var resizeIndex: Int
        get() = prefs.getInt("resize", 0)
        set(v) = prefs.edit().putInt("resize", v).apply()

    var sortMode: Int
        get() = prefs.getInt("sort", 0)
        set(v) = prefs.edit().putInt("sort", v).apply()
}
