package com.tvplayer.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Client for the Xtream Codes player_api.php protocol. */
class XtreamApi(private val profile: ServerProfile) {

    enum class Section(val catAction: String, val listAction: String, val kind: ItemKind) {
        LIVE("get_live_categories", "get_live_streams", ItemKind.LIVE),
        MOVIES("get_vod_categories", "get_vod_streams", ItemKind.MOVIE),
        SERIES("get_series_categories", "get_series", ItemKind.SERIES),
    }

    class SeriesData(val details: Details, val episodes: List<Channel>)

    val base: String = normalizeBase(profile.url)
    private val user = profile.username
    private val pass = profile.password
    private val ua = profile.userAgent

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun apiUrl(action: String? = null, extra: String = ""): String {
        val sb = StringBuilder("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}")
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    /** Checks the credentials; returns a short account summary. */
    suspend fun login(): String {
        val text = Http.get(apiUrl(), ua)
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IOException("الرد من السيرفر مش بصيغة Xtream")
        val ui = o.optJSONObject("user_info") ?: throw IOException("الرد من السيرفر مش بصيغة Xtream")
        if (ui.str("auth") != "1") throw IOException("اسم المستخدم أو كلمة المرور غلط")
        val status = ui.str("status")
        val exp = ui.str("exp_date").toLongOrNull()
        val expText = if (exp != null && exp > 0) {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(exp * 1000))
        } else "غير محدد"
        return "الحالة: $status — ينتهي: $expText"
    }

    /** The server's own categories for a section, in the server's order. */
    suspend fun categories(section: Section): List<Category> =
        parseArray(Http.getCached(apiUrl(section.catAction), ua)).map {
            Category(it.str("category_id"), it.str("category_name").ifBlank { "—" })
        }

    /** Items of one category, or of the whole section when [categoryId] is null. */
    suspend fun items(section: Section, categoryId: String?): List<Channel> {
        val extra = if (categoryId != null) "&category_id=${enc(categoryId)}" else ""
        val arr = parseArray(Http.getCached(apiUrl(section.listAction, extra), ua))
        return arr.mapNotNull { o ->
            val cat = o.str("category_id")
            when (section) {
                Section.LIVE -> {
                    val id = o.str("stream_id").ifBlank { return@mapNotNull null }
                    val other = if (profile.liveFormat == "ts") "m3u8" else "ts"
                    Channel(
                        id = id, name = o.str("name"), logo = o.str("stream_icon").ifBlank { null },
                        group = cat, url = liveUrl(id, profile.liveFormat),
                        kind = ItemKind.LIVE, fallbackUrl = liveUrl(id, other),
                        added = o.str("added").toLongOrNull() ?: 0,
                    )
                }
                Section.MOVIES -> {
                    val id = o.str("stream_id").ifBlank { return@mapNotNull null }
                    val ext = o.str("container_extension").ifBlank { "mp4" }
                    Channel(
                        id = id, name = o.str("name"), logo = o.str("stream_icon").ifBlank { null },
                        group = cat, url = "$base/movie/$user/$pass/$id.$ext", kind = ItemKind.MOVIE,
                        rating = cleanRating(o.str("rating")),
                        added = o.str("added").toLongOrNull() ?: 0,
                        year = o.str("year").ifBlank { o.str("releasedate").take(4) },
                    )
                }
                Section.SERIES -> {
                    val id = o.str("series_id").ifBlank { return@mapNotNull null }
                    Channel(
                        id = id, name = o.str("name"), logo = o.str("cover").ifBlank { null },
                        group = cat, url = "", kind = ItemKind.SERIES,
                        rating = cleanRating(o.str("rating")),
                        added = o.str("last_modified").toLongOrNull() ?: 0,
                        year = o.str("year").ifBlank { o.str("releaseDate").ifBlank { o.str("release_date") }.take(4) },
                    )
                }
            }
        }
    }

    suspend fun vodInfo(vodId: String): Details {
        val o = runCatching { JSONObject(Http.getCached(apiUrl("get_vod_info", "&vod_id=${enc(vodId)}"), ua, 24 * 3600_000L)) }
            .getOrElse { throw IOException("تعذّر تحميل تفاصيل الفيلم") }
        val i = o.optJSONObject("info") ?: JSONObject()
        return Details(
            title = i.str("name").ifBlank { o.optJSONObject("movie_data")?.str("name").orEmpty() },
            poster = i.str("movie_image").ifBlank { i.str("cover_big") }.ifBlank { null },
            backdrop = backdrop(i),
            plot = i.str("plot").ifBlank { i.str("description") },
            genre = i.str("genre"),
            cast = i.str("cast").ifBlank { i.str("actors") },
            director = i.str("director"),
            release = i.str("releasedate").ifBlank { i.str("release_date") },
            rating = cleanRating(i.str("rating")),
            duration = i.str("duration"),
        )
    }

    suspend fun seriesInfo(seriesId: String): SeriesData {
        val o = runCatching { JSONObject(Http.getCached(apiUrl("get_series_info", "&series_id=${enc(seriesId)}"), ua, 3600_000L)) }
            .getOrElse { throw IOException("تعذّر تحميل حلقات المسلسل") }
        val i = o.optJSONObject("info") ?: JSONObject()
        val details = Details(
            title = i.str("name"),
            poster = i.str("cover").ifBlank { null },
            backdrop = backdrop(i),
            plot = i.str("plot"),
            genre = i.str("genre"),
            cast = i.str("cast"),
            director = i.str("director"),
            release = i.str("releaseDate").ifBlank { i.str("release_date") },
            rating = cleanRating(i.str("rating")),
        )

        val raw = ArrayList<JSONObject>()
        when (val eps = o.opt("episodes")) {
            // { "1": [ ... ], "2": [ ... ] }
            is JSONObject -> eps.keys().asSequence()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .forEach { k -> eps.optJSONArray(k)?.let { raw += it.objects() } }
            // [ [ ... ], [ ... ] ]  or  [ {...}, {...} ]
            is JSONArray -> for (n in 0 until eps.length()) {
                when (val e = eps.opt(n)) {
                    is JSONArray -> raw += e.objects()
                    is JSONObject -> raw += e
                }
            }
        }
        val episodes = raw.mapNotNull { e ->
            val id = e.str("id").ifBlank { return@mapNotNull null }
            val ext = e.str("container_extension").ifBlank { "mp4" }
            val season = e.str("season").ifBlank { "1" }
            val num = e.str("episode_num")
            val title = e.str("title").ifBlank { "الحلقة $num" }
            Channel(
                id = id,
                name = "S$season E$num • $title",
                logo = e.optJSONObject("info")?.str("movie_image")?.ifBlank { null } ?: details.poster,
                group = season,
                url = "$base/series/$user/$pass/$id.$ext",
                kind = ItemKind.EPISODE,
                seriesId = seriesId,
                seriesName = details.title,
            )
        }.sortedWith(compareBy({ it.group.toIntOrNull() ?: 0 }, { episodeNumber(it.name) }))
        return SeriesData(details, episodes)
    }

    private fun episodeNumber(name: String): Int =
        Regex("""E(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun backdrop(i: JSONObject): String? = when (val b = i.opt("backdrop_path")) {
        is JSONArray -> b.optString(0).ifBlank { null }
        is String -> b.ifBlank { null }
        else -> null
    }

    private fun cleanRating(r: String): String {
        val d = r.toDoubleOrNull() ?: return ""
        return if (d <= 0.0) "" else String.format(java.util.Locale.US, "%.1f", d)
    }

    private fun liveUrl(id: String, ext: String) = "$base/live/$user/$pass/$id.$ext"

    private fun parseArray(text: String): List<JSONObject> {
        val t = text.trim()
        return when {
            t.startsWith("[") -> JSONArray(t).objects()
            t.startsWith("{") -> {
                // some panels return an object keyed by id instead of an array
                val o = JSONObject(t)
                o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList()
            }
            else -> emptyList()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        /** Accepts "host:port", "http://host:port/", or a full get.php / player_api.php link. */
        fun normalizeBase(raw: String): String {
            var s = Http.fixUrl(raw)
            s = s.substringBefore('?').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }
    }
}
