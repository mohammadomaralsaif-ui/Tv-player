package com.tvplayer.app.data

import java.net.URLDecoder

/**
 * Parses M3U / M3U8 playlists, including:
 *  - #EXTINF attributes (tvg-id, tvg-name, tvg-logo, group-title)
 *  - #EXTGRP groups
 *  - #EXTVLCOPT:http-user-agent / http-referrer
 *  - Kodi-style headers:  http://host/stream.m3u8|User-Agent=xxx&Referer=yyy
 */
object M3uParser {
    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val movieExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var title: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        val headers = HashMap<String, String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = extractTitle(line)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':').trim()
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    var url = line
                    if (url.contains('|')) {
                        val extra = url.substringAfter('|')
                        url = url.substringBefore('|')
                        extra.split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim()
                            val v = kv.substringAfter('=', "").trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) {
                                headers[normalizeHeader(k)] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                            }
                        }
                    }
                    val name = title?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: "قناة ${out.size + 1}"
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                        ?: extGroup?.takeIf { it.isNotBlank() }
                        ?: "بدون تصنيف"
                    out += Channel(
                        id = "m${out.size}",
                        name = name,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = group,
                        url = url,
                        kind = guessKind(url),
                        headers = HashMap(headers),
                    )
                    title = null
                    attrs = emptyMap()
                    extGroup = null
                    headers.clear()
                }
            }
        }
        return out
    }

    fun guessKind(url: String): ItemKind {
        val l = url.lowercase().substringBefore('?')
        return when {
            l.contains("/movie/") -> ItemKind.MOVIE
            l.contains("/series/") -> ItemKind.EPISODE
            movieExt.any { l.endsWith(it) } -> ItemKind.MOVIE
            else -> ItemKind.LIVE
        }
    }

    /** Title is whatever follows the first comma that is not inside quotes. */
    private fun extractTitle(line: String): String? {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return null
    }

    private fun normalizeHeader(k: String): String = when (k.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> k
    }
}
