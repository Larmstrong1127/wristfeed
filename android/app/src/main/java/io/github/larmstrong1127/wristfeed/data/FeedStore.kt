package io.github.larmstrong1127.wristfeed.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.github.larmstrong1127.wristfeed.core.Feed
import io.github.larmstrong1127.wristfeed.core.FeedParser
import io.github.larmstrong1127.wristfeed.core.FeedState
import io.github.larmstrong1127.wristfeed.core.FetchOutcome
import io.github.larmstrong1127.wristfeed.core.ParseResult
import io.github.larmstrong1127.wristfeed.core.StateMachine
import java.util.UUID

/**
 * One configured feed and what we know about it.
 * The last good copy is stored as its raw body (re-parsed on read) plus its download time, so the
 * persisted format never drifts from the parser.
 */
data class FeedRecord(
    val id: String,
    val url: String,
    val lastGoodBody: ByteArray?,
    val lastGoodFetchedAtMs: Long?,
    val lastOutcome: FetchOutcome,
    val lastReason: String?,
    val lastAttemptMs: Long?,
) {
    val lastGood: Feed?
        get() {
            val body = lastGoodBody ?: return null
            val at = lastGoodFetchedAtMs ?: return null
            return (FeedParser.parse(body, at) as? ParseResult.Ok)?.feed
        }

    fun state(nowMs: Long = System.currentTimeMillis()): FeedState =
        StateMachine.resolve(lastGood, lastOutcome, lastReason, nowMs)
}

/** Up to [MAX_FEEDS] feeds in SharedPreferences. Writes use commit() so the worker and tile see them at once. */
class FeedStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("wristfeed", Context.MODE_PRIVATE)

    companion object {
        const val MAX_FEEDS = 5
        private const val KEY_IDS = "ids"
        private const val KEY_TILE_FEED = "tile_feed_id"
    }

    @Synchronized
    fun ids(): List<String> = prefs.getString(KEY_IDS, "")!!.split(',').filter { it.isNotEmpty() }

    @Synchronized
    fun all(): List<FeedRecord> = ids().mapNotNull { get(it) }

    @Synchronized
    fun get(id: String): FeedRecord? {
        val url = prefs.getString("$id.url", null) ?: return null
        return FeedRecord(
            id = id,
            url = url,
            lastGoodBody = prefs.getString("$id.body", null)?.let { Base64.decode(it, Base64.NO_WRAP) },
            lastGoodFetchedAtMs = prefs.getLong("$id.goodAt", -1).takeIf { it >= 0 },
            lastOutcome = prefs.getString("$id.outcome", null)?.let { runCatching { FetchOutcome.valueOf(it) }.getOrNull() }
                ?: FetchOutcome.NONE,
            lastReason = prefs.getString("$id.reason", null),
            lastAttemptMs = prefs.getLong("$id.attemptAt", -1).takeIf { it >= 0 },
        )
    }

    /** Returns the new id, or null when the store is full or the URL is not https. */
    @Synchronized
    fun add(url: String): String? {
        val clean = url.trim()
        if (!clean.startsWith("https://", ignoreCase = true) || clean.length <= 8) return null
        val ids = ids()
        if (ids.size >= MAX_FEEDS) return null
        val id = UUID.randomUUID().toString().substring(0, 8)
        prefs.edit()
            .putString("$id.url", clean)
            .putString(KEY_IDS, (ids + id).joinToString(","))
            .commit()
        return id
    }

    @Synchronized
    fun remove(id: String) {
        val e = prefs.edit()
        listOf("url", "body", "goodAt", "outcome", "reason", "attemptAt").forEach { e.remove("$id.$it") }
        e.putString(KEY_IDS, ids().filter { it != id }.joinToString(","))
        if (prefs.getString(KEY_TILE_FEED, null) == id) e.remove(KEY_TILE_FEED)
        e.commit()
    }

    /** Records a fetch attempt. A good body replaces the stored copy; failures keep it (SPEC). */
    @Synchronized
    fun recordFetch(id: String, outcome: FetchOutcome, reason: String?, goodBody: ByteArray?, atMs: Long) {
        if (prefs.getString("$id.url", null) == null) return // deleted meanwhile
        val e = prefs.edit()
            .putString("$id.outcome", outcome.name)
            .putLong("$id.attemptAt", atMs)
        if (reason != null) e.putString("$id.reason", reason) else e.remove("$id.reason")
        if (outcome == FetchOutcome.OK && goodBody != null) {
            e.putString("$id.body", Base64.encodeToString(goodBody, Base64.NO_WRAP))
            e.putLong("$id.goodAt", atMs)
        }
        e.commit()
    }

    /** Which feed the tile shows: the chosen one if still present, else the first. */
    @Synchronized
    fun tileFeed(): FeedRecord? {
        val chosen = prefs.getString(KEY_TILE_FEED, null)?.let { get(it) }
        return chosen ?: ids().firstOrNull()?.let { get(it) }
    }

    @Synchronized
    fun setTileFeed(id: String) {
        prefs.edit().putString(KEY_TILE_FEED, id).commit()
    }

    fun tileFeedId(): String? = tileFeed()?.id
}
