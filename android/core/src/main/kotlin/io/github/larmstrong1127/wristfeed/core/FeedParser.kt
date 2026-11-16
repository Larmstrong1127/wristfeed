package io.github.larmstrong1127.wristfeed.core

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Outcome of parsing one downloaded body. Mirrors SPEC.md rules 1-5. */
sealed class ParseResult {
    /** [fallback] is true when any optional field or row was wrong and fell back (SPEC rule 3). */
    data class Ok(val feed: Feed, val fallback: Boolean = false) : ParseResult()
    data class Malformed(val reason: String) : ParseResult()
    data class Unsupported(val version: Long) : ParseResult()
}

/**
 * The v1 parser. Its decisions match the Python reference (publisher/src/wristfeed/feed.py) and
 * the shared corpus in conformance/: blank title is malformed, numeric row values are not coerced
 * (row dropped), a bad row status keeps the row uncolored, progress needs a string label.
 */
object FeedParser {
    /** SPEC rule 5: the app reads at most 64 KB; anything larger is malformed. */
    const val MAX_BYTES = 64 * 1024

    /** SPEC rule 4 tolerance. */
    const val SKEW_TOLERANCE_MS = 2 * 60 * 1000L

    /** SPEC: wristfeed must be an integer in -2^53..2^53. */
    const val MAX_VERSION_MAGNITUDE = 1L shl 53

    /** SPEC: stale_after_minutes is 1..525600 (one year), else the default. */
    const val MAX_STALE_AFTER_MINUTES = 525_600L

    /** SPEC rule 7: a number with a larger magnitude is "not a number". */
    const val MAX_NUMBER_MAGNITUDE = 1e308

    /** SPEC: offsets beyond +-18:00 are rejected. */
    private const val MAX_OFFSET_MINUTES = 18 * 60

    private fun finiteNumber(v: Any?): Double? =
        (v as? Number)?.toDouble()?.takeIf { it.isFinite() && Math.abs(it) <= MAX_NUMBER_MAGNITUDE }

    private val STATUSES = mapOf("ok" to Status.OK, "warn" to Status.WARN, "bad" to Status.BAD, "idle" to Status.IDLE)
    private val KINDS = mapOf("stats" to Kind.STATS, "progress" to Kind.PROGRESS, "message" to Kind.MESSAGE)

    /**
     * Parses raw bytes. [fetchedAtMs] is the download time, used by the clock-skew guard
     * (SPEC rule 4, credit: Wrist-Usage): an `updated` more than 2 minutes after the
     * download is replaced by the download time. Pass `Long.MAX_VALUE` to disable the guard.
     */
    fun parse(bytes: ByteArray, fetchedAtMs: Long): ParseResult {
        if (bytes.size > MAX_BYTES) return ParseResult.Malformed("larger than 64 KB")
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return ParseResult.Malformed("not UTF-8")
        }
        return parseText(text, fetchedAtMs)
    }

    fun parse(text: String, fetchedAtMs: Long): ParseResult = parse(text.toByteArray(Charsets.UTF_8), fetchedAtMs)

    private fun parseText(text: String, fetchedAtMs: Long): ParseResult {
        val root = try {
            MiniJson.parse(text.removePrefix("\uFEFF"))
        } catch (e: JsonParseException) {
            return ParseResult.Malformed("not JSON")
        }
        val obj = root as? Map<*, *> ?: return ParseResult.Malformed("not a JSON object")

        // Required: wristfeed (integer). Checked first so a v2 feed reads "unsupported", not "malformed".
        if (!obj.containsKey("wristfeed")) return ParseResult.Malformed("missing wristfeed")
        val version = obj["wristfeed"] as? Long ?: return ParseResult.Malformed("wristfeed is not an integer")
        if (version < -MAX_VERSION_MAGNITUDE || version > MAX_VERSION_MAGNITUDE) {
            return ParseResult.Malformed("wristfeed is out of range")
        }
        if (version != Feed.SUPPORTED_VERSION) return ParseResult.Unsupported(version)

        // Required: title (non-blank string).
        if (!obj.containsKey("title")) return ParseResult.Malformed("missing title")
        val title = (obj["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: return ParseResult.Malformed("title must be a non-empty string")

        // Required: updated (RFC 3339, Z or offset).
        if (!obj.containsKey("updated")) return ParseResult.Malformed("missing updated")
        val updatedMs = (obj["updated"] as? String)?.let { parseRfc3339(it) }
            ?: return ParseResult.Malformed("updated is not RFC 3339")
        val guardedUpdated = if (fetchedAtMs != Long.MAX_VALUE && updatedMs - fetchedAtMs > SKEW_TOLERANCE_MS) fetchedAtMs else updatedMs

        // Optional fields: wrong type / out of range -> default (rule 3). Unknown fields ignored (rule 1).
        var fallback = false
        fun <T> enumField(key: String, table: Map<String, T>, default: T): T {
            if (!obj.containsKey(key)) return default
            return (obj[key] as? String)?.let { table[it] } ?: default.also { fallback = true }
        }
        val status = enumField("status", STATUSES, Status.IDLE)
        val kind = enumField("kind", KINDS, Kind.STATS)

        val rows = ArrayList<Row>()
        if (obj.containsKey("rows")) {
            val raw = obj["rows"] as? List<*>
            if (raw == null) fallback = true
            raw?.forEach { r ->
                val m = r as? Map<*, *>
                val label = m?.get("label") as? String
                val value = m?.get("value") as? String
                if (m == null || label == null || value == null) {
                    fallback = true // row dropped
                } else {
                    val st = m["status"]
                    val rowStatus: Status? = if (st == null) null else (st as? String)?.let { STATUSES[it] }
                    if (st != null && rowStatus == null) fallback = true
                    rows.add(Row(label, value, rowStatus))
                }
            }
        }

        var progress: Progress? = null
        val rawProgress = obj["progress"]
        if (rawProgress != null) {
            val m = rawProgress as? Map<*, *>
            val label = m?.get("label") as? String
            val value = finiteNumber(m?.get("value"))
            val max = finiteNumber(m?.get("max"))
            if (label == null || value == null || max == null || max <= 0.0) fallback = true
            else progress = Progress(label, value.coerceIn(0.0, max), max)
        }

        val rawMessage = obj["message"]
        val message = rawMessage as? String
        if (rawMessage != null && message == null) fallback = true

        var staleAfter = Feed.DEFAULT_STALE_AFTER_MINUTES
        if (obj.containsKey("stale_after_minutes")) {
            val v = obj["stale_after_minutes"] as? Long
            if (v != null && v in 1L..MAX_STALE_AFTER_MINUTES) staleAfter = v.toInt() else fallback = true
        }

        val rawTap = obj["tap_url"]
        val tapUrl = (rawTap as? String)?.takeIf { it.length > 8 && it.substring(0, 8).equals("https://", ignoreCase = true) }
        if (rawTap != null && tapUrl == null) fallback = true

        return ParseResult.Ok(
            Feed(
                title = title,
                updatedEpochMs = guardedUpdated,
                status = status,
                kind = kind,
                rows = rows,
                progress = progress,
                message = message,
                staleAfterMinutes = staleAfter,
                tapUrl = tapUrl,
            ),
            fallback,
        )
    }

    /**
     * RFC 3339 with `Z` or an offset; no zone = rejected; 1-9 fraction digits accepted;
     * offset minutes < 60 and |offset| <= 18:00 (SPEC, same check as the Python reference).
     */
    fun parseRfc3339(s: String): Long? {
        val m = RFC3339.matchEntire(s) ?: return null
        val oh = m.groupValues[3]
        if (oh.isNotEmpty()) {
            val om = m.groupValues[4].toInt()
            if (om >= 60 || oh.toInt() * 60 + om > MAX_OFFSET_MINUTES) return null
        }
        return try {
            OffsetDateTime.parse(s.uppercase()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val RFC3339 = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d{1,9})?([Zz]|[+-](\d{2}):(\d{2}))""")
}
