package io.github.larmstrong1127.wristfeed.core

/** Display rules from SPEC.md: truncation, age footer, complication text, state headlines. */
object Display {
    const val ELLIPSIS = "\u2026"
    const val DOT = "\u00B7"
    const val MAX_TITLE = 24
    const val MAX_LABEL = 12
    const val MAX_VALUE = 10
    const val MAX_MESSAGE = 60
    const val MAX_ROWS = 3

    /**
     * At most [max] characters (code points) are shown; longer text keeps max-1 and adds the
     * ellipsis, so the result is never longer than [max].
     */
    fun truncate(text: String, max: Int): String {
        val cps = text.codePointCount(0, text.length)
        if (cps <= max) return text
        val end = text.offsetByCodePoints(0, max - 1)
        return text.substring(0, end) + ELLIPSIS
    }

    /** "Nm ago" under an hour, else "Nh ago" (floored). Negative ages read as 0m. */
    fun age(ageMs: Long): String {
        val minutes = ageMs.coerceAtLeast(0) / 60_000L
        return if (minutes < 60) "${minutes}m ago" else "${minutes / 60}h ago"
    }

    /**
     * Stale footer: "Old data \u00B7 " + the same age label (so "Old data \u00B7 7h ago"; a feed with a very
     * short stale_after_minutes can read "Old data \u00B7 20m ago"). Matches the Python reference footer().
     */
    fun staleFooter(ageMs: Long): String = "Old data $DOT ${age(ageMs)}"

    fun footer(state: FeedState): String? = when (state) {
        is FeedState.Fresh -> age(state.ageMs)
        is FeedState.Stale -> staleFooter(state.ageMs)
        else -> null
    }

    fun title(feed: Feed) = truncate(feed.title, MAX_TITLE)

    fun shownRows(feed: Feed): List<Row> = feed.rows.take(MAX_ROWS).map {
        Row(truncate(it.label, MAX_LABEL), truncate(it.value, MAX_VALUE), it.status)
    }

    fun message(feed: Feed): String? = feed.message?.let { truncate(it, MAX_MESSAGE) }

    /** Integral numbers print without ".0" ("1/5"); others like Python's "%g" (6 significant digits). */
    fun formatNumber(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else java.math.BigDecimal(d).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

    fun progressText(p: Progress) = "${formatNumber(p.value)}/${formatNumber(p.max)}"

    /** SHORT_TEXT text: first row's value (stats), value/max (progress), title (message). */
    fun shortText(feed: Feed): String = when (feed.kind) {
        Kind.STATS -> feed.rows.firstOrNull()?.let { truncate(it.value, MAX_VALUE) } ?: title(feed)
        Kind.PROGRESS -> feed.progress?.let { progressText(it) } ?: title(feed)
        Kind.MESSAGE -> title(feed)
    }

    /** SHORT_TEXT label: the title. */
    fun shortTitle(feed: Feed): String = title(feed)

    /**
     * LONG_TEXT: "title: row1 \u00B7 row2" (each row "label value") or the message.
     * For a progress feed: "title: label value/max".
     */
    fun longText(feed: Feed): String = when (feed.kind) {
        Kind.MESSAGE -> message(feed) ?: title(feed)
        Kind.PROGRESS -> feed.progress?.let { p ->
            val label = truncate(p.label, MAX_LABEL)
            "${title(feed)}: " + listOf(label, progressText(p)).filter { it.isNotEmpty() }.joinToString(" ")
        } ?: title(feed)
        Kind.STATS -> {
            val rows = shownRows(feed)
            if (rows.isEmpty()) title(feed)
            else "${title(feed)}: " + rows.joinToString(" $DOT ") { "${it.label} ${it.value}" }
        }
    }

    /** Headline for non-data states (null for fresh/stale). */
    fun stateHeadline(state: FeedState): String? = when (state) {
        FeedState.Loading -> "Loading$ELLIPSIS"
        FeedState.Unreachable -> "Can't reach feed"
        is FeedState.Malformed -> "Bad feed"
        FeedState.Unsupported -> "Unsupported feed version"
        else -> null
    }
}
