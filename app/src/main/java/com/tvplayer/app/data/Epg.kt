package com.tvplayer.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Program(val title: String, val desc: String, val start: Long, val end: Long) {
    fun percent(now: Long = System.currentTimeMillis()): Int =
        if (end > start) (((now - start) * 100) / (end - start)).toInt().coerceIn(0, 100) else 0

    fun timeRange(): String = "${Epg.clock(start)} – ${Epg.clock(end)}"
}

/**
 * Program guide for live channels (what's on now / next), loaded on demand
 * per channel and kept in memory. Only a few requests run at once.
 */
object Epg {
    private val cache = HashMap<String, List<Program>>()
    private val fetchedAt = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private val limit = Semaphore(4)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun clock(ms: Long): String = clockFormat.format(Date(ms))

    private fun key(profileId: String, streamId: String) = "$profileId:$streamId"

    fun now(profileId: String, streamId: String): Program? {
        val t = System.currentTimeMillis()
        return synchronized(this) { cache[key(profileId, streamId)] }?.firstOrNull { it.start <= t && t < it.end }
    }

    fun next(profileId: String, streamId: String): Program? {
        val t = System.currentTimeMillis()
        val list = synchronized(this) { cache[key(profileId, streamId)] } ?: return null
        val cur = list.firstOrNull { it.start <= t && t < it.end }
        return list.firstOrNull { it.start >= (cur?.end ?: t) }
    }

    /** Loads the guide for a channel if it isn't fresh; calls [onReady] when new data arrives. */
    fun request(scope: CoroutineScope, api: XtreamApi, profileId: String, streamId: String, onReady: () -> Unit) {
        val k = key(profileId, streamId)
        synchronized(this) {
            val age = System.currentTimeMillis() - (fetchedAt[k] ?: 0L)
            val fresh = age < 20 * 60_000L && (cache[k]?.isEmpty() == true || now(profileId, streamId) != null)
            if (fresh || k in inFlight) return
            inFlight += k
        }
        scope.launch {
            val list = try {
                limit.withPermit { api.shortEpg(streamId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    synchronized(this@Epg) { inFlight -= k }
                    throw e
                }
                emptyList()
            }
            synchronized(this@Epg) {
                cache[k] = list
                fetchedAt[k] = System.currentTimeMillis()
                inFlight -= k
            }
            if (list.isNotEmpty()) onReady()
        }
    }
}
