package com.tvplayer.app.data

import org.json.JSONObject

enum class ServerType { XTREAM, M3U, DIRECT }

enum class ItemKind { LIVE, MOVIE, SERIES, EPISODE }

/** Reads a JSON value as a string, treating JSON null / missing as "". */
fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

data class ServerProfile(
    val id: String,
    val name: String,
    val type: ServerType,
    /** Xtream: server base url. M3U: playlist url. DIRECT: stream url. */
    val url: String,
    val username: String = "",
    val password: String = "",
    val userAgent: String = "",
    /** Xtream live output: "m3u8" or "ts". */
    val liveFormat: String = "m3u8",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("url", url)
        put("username", username)
        put("password", Crypto.encrypt(password))
        put("userAgent", userAgent)
        put("liveFormat", liveFormat)
    }

    companion object {
        fun fromJson(o: JSONObject) = ServerProfile(
            id = o.str("id"),
            name = o.str("name"),
            type = runCatching { ServerType.valueOf(o.str("type")) }.getOrDefault(ServerType.M3U),
            url = o.str("url"),
            username = o.str("username"),
            password = Crypto.decrypt(o.str("password")),
            userAgent = o.str("userAgent"),
            liveFormat = o.str("liveFormat").ifBlank { "m3u8" },
        )
    }
}

data class Category(val id: String, val name: String, val count: Int = -1)

data class Channel(
    val id: String,
    val name: String,
    val logo: String?,
    val group: String,
    /** Playable url; empty for a series (which opens its details page). */
    val url: String,
    val kind: ItemKind,
    val fallbackUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val rating: String = "",
    /** Unix seconds the server says the item was added (0 = unknown). */
    val added: Long = 0,
    val year: String = "",
    val seriesId: String? = null,
    val seriesName: String? = null,
    /** Human-readable length, e.g. "45 د" (episodes). */
    val duration: String = "",
) {
    val favKey: String get() = "${kind.name}:${url.ifEmpty { id }}"

    /** Continue-watching key: one entry per series, one per movie. */
    val watchKey: String
        get() = if (kind == ItemKind.EPISODE && !seriesId.isNullOrEmpty()) "S:$seriesId" else "V:$url"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("logo", logo ?: "")
        put("group", group)
        put("url", url)
        put("kind", kind.name)
        put("fallbackUrl", fallbackUrl ?: "")
        put("headers", JSONObject(headers))
        put("rating", rating)
        put("added", added)
        put("year", year)
        put("seriesId", seriesId ?: "")
        put("seriesName", seriesName ?: "")
        put("duration", duration)
    }

    companion object {
        fun fromJson(o: JSONObject): Channel {
            val h = HashMap<String, String>()
            o.optJSONObject("headers")?.let { ho ->
                ho.keys().forEach { k -> h[k] = ho.str(k) }
            }
            return Channel(
                id = o.str("id"),
                name = o.str("name"),
                logo = o.str("logo").ifBlank { null },
                group = o.str("group"),
                url = o.str("url"),
                kind = runCatching { ItemKind.valueOf(o.str("kind")) }.getOrDefault(ItemKind.LIVE),
                fallbackUrl = o.str("fallbackUrl").ifBlank { null },
                headers = h,
                rating = o.str("rating"),
                added = o.str("added").toLongOrNull() ?: 0,
                year = o.str("year"),
                seriesId = o.str("seriesId").ifBlank { null },
                seriesName = o.str("seriesName").ifBlank { null },
                duration = o.str("duration"),
            )
        }
    }
}

/** Info shown on a movie / series page. */
data class Details(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String = "",
    val genre: String = "",
    val cast: String = "",
    val director: String = "",
    val release: String = "",
    val rating: String = "",
    val duration: String = "",
)

data class WatchEntry(val channel: Channel, val position: Long, val duration: Long, val updated: Long) {
    val percent: Int get() = if (duration > 0) ((position * 100) / duration).toInt().coerceIn(0, 100) else 0
}

/** Hands the list being played from a browser screen to the player screen. */
object PlayerQueue {
    /** Start position meaning "resume from saved progress if any". */
    const val RESUME = -1L

    var items: List<Channel> = emptyList()
    var index: Int = 0
    var userAgent: String? = null
    var profileId: String? = null
    var startPosition: Long = RESUME

    fun set(list: List<Channel>, position: Int, profile: ServerProfile, start: Long = RESUME) {
        items = list
        index = position
        userAgent = profile.userAgent
        profileId = profile.id
        startPosition = start
    }
}

/** In-memory cache of what was loaded from the current server (shared by browse and search). */
object Library {
    private var profileId: String? = null
    var m3u: List<Channel> = emptyList()
    val categories = HashMap<XtreamApi.Section, List<Category>>()
    /** key = "SECTION:categoryId", or "SECTION:*" for everything in the section. */
    val items = HashMap<String, List<Channel>>()

    fun clear() {
        profileId = null
        m3u = emptyList()
        categories.clear()
        items.clear()
    }

    fun bind(id: String) {
        if (profileId != id) {
            profileId = id
            m3u = emptyList()
            categories.clear()
            items.clear()
        }
    }
}
