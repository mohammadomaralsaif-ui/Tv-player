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

    suspend fun get(url: String, userAgent: String?): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", ua(userAgent)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("السيرفر رد بخطأ HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }
}
