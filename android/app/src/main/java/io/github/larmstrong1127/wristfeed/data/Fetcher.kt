package io.github.larmstrong1127.wristfeed.data

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import io.github.larmstrong1127.wristfeed.complication.FeedComplicationService
import io.github.larmstrong1127.wristfeed.core.FeedParser
import io.github.larmstrong1127.wristfeed.core.FetchOutcome
import io.github.larmstrong1127.wristfeed.core.ParseResult
import io.github.larmstrong1127.wristfeed.tile.FeedTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Raw HTTP result before parsing. */
sealed class HttpResult {
    class Body(val bytes: ByteArray) : HttpResult()
    object TooLarge : HttpResult()
    data class Failed(val why: String) : HttpResult()
}

object Fetcher {
    private const val TIMEOUT_MS = 10_000

    /** HTTPS GET with 10 s timeouts; reads at most 64 KB + 1 byte so "too large" is detectable. */
    fun get(url: String): HttpResult {
        if (!url.startsWith("https://", ignoreCase = true)) return HttpResult.Failed("not https")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true // same-protocol redirects only (HttpURLConnection never goes https -> http)
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "WristFeed/0.1")
            }
            val code = conn.responseCode
            if (code != 200) return HttpResult.Failed("HTTP $code")
            if (conn.contentLengthLong > FeedParser.MAX_BYTES) return HttpResult.TooLarge
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > FeedParser.MAX_BYTES) return HttpResult.TooLarge
                }
            }
            HttpResult.Body(out.toByteArray())
        } catch (e: IOException) {
            HttpResult.Failed(e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            HttpResult.Failed("bad URL")
        } finally {
            conn?.disconnect()
        }
    }
}

/** Fetch + parse + store + poke the tile and complications. */
object Refresher {
    suspend fun refreshAll(context: Context) {
        val store = FeedStore(context)
        store.ids().forEach { refreshOne(context, store, it, notify = false) }
        notifySurfaces(context)
    }

    suspend fun refreshOne(context: Context, store: FeedStore, id: String, notify: Boolean = true) {
        val record = store.get(id) ?: return
        val now = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { Fetcher.get(record.url) }
        when (result) {
            is HttpResult.Failed -> store.recordFetch(id, FetchOutcome.UNREACHABLE, result.why, null, now)
            HttpResult.TooLarge -> store.recordFetch(id, FetchOutcome.MALFORMED, "larger than 64 KB", null, now)
            is HttpResult.Body -> when (val parsed = FeedParser.parse(result.bytes, now)) {
                is ParseResult.Ok -> store.recordFetch(id, FetchOutcome.OK, null, result.bytes, now)
                is ParseResult.Malformed -> store.recordFetch(id, FetchOutcome.MALFORMED, parsed.reason, null, now)
                is ParseResult.Unsupported -> store.recordFetch(id, FetchOutcome.UNSUPPORTED, "version ${parsed.version}", null, now)
            }
        }
        if (notify) notifySurfaces(context)
    }

    fun notifySurfaces(context: Context) {
        TileService.getUpdater(context).requestUpdate(FeedTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, FeedComplicationService::class.java))
            .requestUpdateAll()
    }
}
