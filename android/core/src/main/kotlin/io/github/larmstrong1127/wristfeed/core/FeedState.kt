package io.github.larmstrong1127.wristfeed.core

/** What the last fetch attempt produced (persisted by the app's store). */
enum class FetchOutcome { NONE, OK, UNREACHABLE, MALFORMED, UNSUPPORTED }

/** The one state a tile shows (SPEC.md "Watch states"). */
sealed class FeedState {
    object Loading : FeedState()
    data class Fresh(val feed: Feed, val ageMs: Long) : FeedState()
    data class Stale(val feed: Feed, val ageMs: Long) : FeedState()
    object Unreachable : FeedState()
    data class Malformed(val reason: String) : FeedState()
    object Unsupported : FeedState()
}

object StateMachine {
    /**
     * @param lastGood the last copy that parsed OK (its `updatedEpochMs` already skew-guarded), or null.
     * @param lastOutcome the result of the most recent fetch attempt.
     * @param lastReason short reason for MALFORMED.
     *
     * A good copy always wins over a later failure (SPEC: "never a blank tile"). That includes an
     * UNSUPPORTED result: if a publisher bumps the version we keep the last v1 copy, which then ages
     * into stale. "Unsupported" is shown only when there is no earlier good copy.
     */
    fun resolve(lastGood: Feed?, lastOutcome: FetchOutcome, lastReason: String?, nowMs: Long): FeedState {
        if (lastGood != null) {
            val age = (nowMs - lastGood.updatedEpochMs).coerceAtLeast(0)
            return if (age < lastGood.staleAfterMinutes * 60_000L) FeedState.Fresh(lastGood, age)
            else FeedState.Stale(lastGood, age)
        }
        return when (lastOutcome) {
            FetchOutcome.NONE, FetchOutcome.OK -> FeedState.Loading
            FetchOutcome.UNREACHABLE -> FeedState.Unreachable
            FetchOutcome.MALFORMED -> FeedState.Malformed(lastReason ?: "invalid feed")
            FetchOutcome.UNSUPPORTED -> FeedState.Unsupported
        }
    }

    /** Convenience: state straight from a parse result (used by the conformance test). */
    fun fromParse(result: ParseResult, nowMs: Long): FeedState = when (result) {
        is ParseResult.Ok -> resolve(result.feed, FetchOutcome.OK, null, nowMs)
        is ParseResult.Malformed -> FeedState.Malformed(result.reason)
        is ParseResult.Unsupported -> FeedState.Unsupported
    }

    /** SPEC Refresh: refresh on tile visible when the last fetch is more than 15 minutes old. */
    const val VISIBLE_REFRESH_AFTER_MS = 15 * 60_000L

    fun shouldRefreshOnVisible(lastAttemptMs: Long?, nowMs: Long): Boolean =
        lastAttemptMs == null || nowMs - lastAttemptMs > VISIBLE_REFRESH_AFTER_MS
}

fun FeedState.name(): String = when (this) {
    FeedState.Loading -> "loading"
    is FeedState.Fresh -> "fresh"
    is FeedState.Stale -> "stale"
    FeedState.Unreachable -> "unreachable"
    is FeedState.Malformed -> "malformed"
    FeedState.Unsupported -> "unsupported"
}
