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
            // Redirects are followed by HttpsFixer below, so every hop gets the https fixes too.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(HttpsFixer)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Used by every request in the app (server lists, playlists AND video playback):
     *  - "https://host:80" → "http://host:80" (port 80 never speaks https)
     *  - https that fails the secure handshake → retried once over plain http
     *  - follows redirects itself, applying the same fixes to each hop
     *    (some IPTV servers redirect streams to a broken https address)
     */
    private object HttpsFixer : okhttp3.Interceptor {
        private fun fix(url: okhttp3.HttpUrl): okhttp3.HttpUrl =
            if (url.isHttps && url.port == 80) url.newBuilder().scheme("http").port(80).build() else url

        private fun send(chain: okhttp3.Interceptor.Chain, request: Request): okhttp3.Response {
            val req = request.newBuilder().url(fix(request.url)).build()
            return try {
                chain.proceed(req)
            } catch (e: javax.net.ssl.SSLException) {
                if (!req.url.isHttps) throw e
                val port = req.url.port
                val plain = req.url.newBuilder().scheme("http").port(if (port == 443) 80 else port).build()
                chain.proceed(req.newBuilder().url(plain).build())
            }
        }

        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            var request = chain.request()
            var response = send(chain, request)
            var hops = 0
            while (response.isRedirect && hops++ < 10) {
                val location = response.header("Location") ?: break
                val next = response.request.url.resolve(location) ?: break
                response.close()
                val builder = request.newBuilder().url(next)
                if (response.code == 303 || (request.method == "POST" && response.code in 301..302)) builder.get()
                request = builder.build()
                response = send(chain, request)
            }
            return response
        }
    }

    private var cacheDir: java.io.File? = null

    fun init(ctx: android.content.Context) {
        cacheDir = java.io.File(ctx.cacheDir, "api").apply { mkdirs() }
    }

    private fun cacheFile(url: String): java.io.File? {
        val dir = cacheDir ?: return null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, hash)
    }

    /**
     * Like [get], but keeps a copy on the device: lists open instantly next time,
     * and still open (from the saved copy) if the server is down.
     */
    suspend fun getCached(url: String, userAgent: String?, maxAgeMs: Long = 6 * 3600_000L): String = withContext(Dispatchers.IO) {
        val f = cacheFile(url)
        if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
            runCatching { return@withContext f.readText() }
        }
        try {
            get(url, userAgent).also { t -> if (f != null && t.isNotBlank()) runCatching { f.writeText(t) } }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (f != null && f.exists()) f.readText() else throw e
        }
    }

    /** Forgets all saved server lists (the "refresh" button). */
    fun clearCache() {
        cacheDir?.listFiles()?.forEach { it.delete() }
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
        withHttpFallback(fixUrl(url)) { fetch(it, userAgent) }
    }

    /** Reads just the start of a link: returns (Content-Type, first ~16 KB as text). */
    suspend fun peek(url: String, userAgent: String?): Pair<String, String> = withContext(Dispatchers.IO) {
        withHttpFallback(fixUrl(url)) { u ->
            val req = Request.Builder().url(u).header("User-Agent", ua(userAgent)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("السيرفر رد بخطأ HTTP ${resp.code}")
                val type = resp.header("Content-Type").orEmpty().lowercase()
                val buf = okio.Buffer()
                val src = resp.body?.source()
                if (src != null) {
                    var tries = 0
                    while (buf.size < 16_384 && tries++ < 8) {
                        if (src.read(buf, 16_384 - buf.size) == -1L) break
                    }
                }
                type to buf.readString(Charsets.UTF_8)
            }
        }
    }

    /** Server doesn't actually speak https on this address: retry once over plain http. */
    private inline fun <T> withHttpFallback(url: String, block: (String) -> T): T = try {
        block(url)
    } catch (e: javax.net.ssl.SSLException) {
        if (url.startsWith("https://", true)) block("http://" + url.substring(8)) else throw e
    }

    private fun fetch(url: String, userAgent: String?): String {
        val req = Request.Builder().url(url).header("User-Agent", ua(userAgent)).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("السيرفر رد بخطأ HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }
}
