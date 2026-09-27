package com.tvplayer.app.data

import android.net.Uri
import java.io.IOException

/**
 * Works out what kind of link the user pasted, so they don't have to choose:
 *  - Xtream Codes: username/password given (or inside a get.php link) and player_api.php answers
 *  - M3U playlist: the file starts with #EXTM3U / has #EXTINF entries
 *  - Direct stream: an HLS stream (#EXT-X-...), DASH (<MPD>), or a video/audio file
 */
object ServerDetector {
    class Result(val type: ServerType, val url: String, val username: String, val password: String, val label: String)

    suspend fun detect(rawUrl: String, user: String, pass: String, userAgent: String?): Result {
        val url = Http.fixUrl(rawUrl)
        val uri = Uri.parse(url)
        val u = user.ifEmpty { uri.getQueryParameter("username").orEmpty() }
        val p = pass.ifEmpty { uri.getQueryParameter("password").orEmpty() }
        val path = uri.path.orEmpty().lowercase()

        if (url.startsWith("rtsp://", true) || url.startsWith("rtmp://", true)) {
            return Result(ServerType.DIRECT, url, "", "", "رابط بث مباشر")
        }

        // 1) Credentials available → try Xtream first.
        var xtreamError: Exception? = null
        if (u.isNotEmpty() && p.isNotEmpty()) {
            val base = XtreamApi.normalizeBase(url)
            val probe = ServerProfile(id = "probe", name = "", type = ServerType.XTREAM, url = base, username = u, password = p, userAgent = userAgent.orEmpty())
            try {
                XtreamApi(probe).login()
                return Result(ServerType.XTREAM, base, u, p, "سيرفر Xtream Codes")
            } catch (e: Exception) {
                xtreamError = e
                // A get.php link can still be read as an M3U playlist below.
                if (!path.endsWith("get.php")) throw e
            }
        }

        // 2) Look at what the link returns.
        val (type, head) = try {
            Http.peek(url, userAgent)
        } catch (e: Exception) {
            throw xtreamError ?: e
        }
        val text = head.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            text.startsWith("#EXTM3U") && text.contains("#EXT-X-") ->
                Result(ServerType.DIRECT, url, "", "", "بث HLS مباشر")
            text.startsWith("#EXTM3U") || text.contains("#EXTINF") ->
                Result(ServerType.M3U, url, "", "", "قائمة M3U")
            text.contains("<MPD") -> Result(ServerType.DIRECT, url, "", "", "بث DASH مباشر")
            type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl") ||
                type.contains("octet-stream") || type.contains("mp2t") ->
                Result(ServerType.DIRECT, url, "", "", "رابط فيديو مباشر")
            text.startsWith("{") && text.contains("user_info") ->
                throw IOException("هذا سيرفر Xtream Codes. أدخل اسم المستخدم وكلمة المرور")
            path.isEmpty() || path == "/" ->
                throw IOException("شكله سيرفر Xtream Codes. أدخل اسم المستخدم وكلمة المرور")
            else -> throw IOException("ما قدرت أعرف نوع هذا الرابط. اختر النوع يدوياً")
        }
    }
}
