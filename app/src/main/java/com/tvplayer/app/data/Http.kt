package com.tvplayer.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    /** Many IPTV panels reject unknown players, so default to a VLC user agent. */
    const val DEFAULT_UA = "VLC/3.0.20 LibVLC/3.0.20"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true) // allow http <-> https redirects, common with IPTV
            .retryOnConnectionFailure(true)
            .build()
    }

    fun ua(custom: String?): String = custom?.takeIf { it.isNotBlank() } ?: DEFAULT_UA

    private val HTTPS_PORT_80 = Regex("^https://([^/?#]+):80(?=[/?#]|$)", RegexOption.IGNORE_CASE)

    /**
     * Fixes common link mistakes: adds http:// when missing, and turns
     * "https://host:80" into "http://host:80" (port 80 never speaks TLS).
     */
    fun fixUrl(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true) && !s.contains("://")) s = "http://$s"
        return HTTPS_PORT_80.replace(s) { "http://${it.groupValues[1]}:80" }
    }

    suspend fun get(url: String, userAgent: String?): String = withContext(Dispatchers.IO) {
        val fixed = fixUrl(url)
        try {
            fetch(fixed, userAgent)
        } catch (e: javax.net.ssl.SSLException) {
            // Server doesn't actually speak https on this address: retry over plain http.
            if (fixed.startsWith("https://", true)) fetch("http://" + fixed.substring(8), userAgent) else throw e
        }
    }

    private fun fetch(url: String, userAgent: String?): String {
        val req = Request.Builder().url(url).header("User-Agent", ua(userAgent)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("السيرفر رد بخطأ HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }
}
