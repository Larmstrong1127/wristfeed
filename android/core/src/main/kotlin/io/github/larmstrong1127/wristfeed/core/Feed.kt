package io.github.larmstrong1127.wristfeed.core

/** Model of a parsed WristFeed v1 document (SPEC.md "Document"). */
enum class Status { OK, WARN, BAD, IDLE }

enum class Kind { STATS, PROGRESS, MESSAGE }

data class Row(val label: String, val value: String, val status: Status?)

data class Progress(val label: String, val value: Double, val max: Double) {
    val fraction: Double get() = if (max > 0) value / max else 0.0
}

data class Feed(
    val title: String,
    val updatedEpochMs: Long,
    val status: Status = Status.IDLE,
    val kind: Kind = Kind.STATS,
    /** Every valid row; display code shows at most [Display.MAX_ROWS]. */
    val rows: List<Row> = emptyList(),
    val progress: Progress? = null,
    val message: String? = null,
    val staleAfterMinutes: Int = Feed.DEFAULT_STALE_AFTER_MINUTES,
    val tapUrl: String? = null,
) {
    companion object {
        const val DEFAULT_STALE_AFTER_MINUTES = 360
        const val SUPPORTED_VERSION = 1L
    }
}
