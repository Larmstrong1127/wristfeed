package io.github.larmstrong1127.wristfeed.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.larmstrong1127.wristfeed.core.Display
import io.github.larmstrong1127.wristfeed.core.Feed
import io.github.larmstrong1127.wristfeed.core.FeedState
import io.github.larmstrong1127.wristfeed.core.Kind
import io.github.larmstrong1127.wristfeed.core.Progress
import io.github.larmstrong1127.wristfeed.core.Row
import io.github.larmstrong1127.wristfeed.data.FeedStore
import io.github.larmstrong1127.wristfeed.ui.MainActivity

/**
 * SPEC Complications table:
 *  SHORT_TEXT   first row value / "value/max" / title, with the title as label
 *  LONG_TEXT    "title: row1 · row2" or the message
 *  RANGED_VALUE progress when present; otherwise NoData ("not offered")
 * Uses the same feed as the tile. Error states show a short status word.
 */
class FeedComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val record = FeedStore(this).tileFeed() ?: return NoDataComplicationData()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val feed = when (val s = record.state()) {
            is FeedState.Fresh -> s.feed
            is FeedState.Stale -> s.feed
            else -> return errorData(request.complicationType, s, tap)
        }
        return build(request.complicationType, feed, tap)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, PREVIEW, null)

    private fun text(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

    private fun build(type: ComplicationType, feed: Feed, tap: PendingIntent?): ComplicationData? {
        val cd = text(Display.longText(feed))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(Display.shortText(feed)), cd)
                .setTitle(text(Display.shortTitle(feed)))
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(Display.longText(feed)), cd)
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> {
                val p = feed.progress ?: return NoDataComplicationData()
                RangedValueComplicationData.Builder(p.value.toFloat(), 0f, p.max.toFloat(), cd)
                    .setText(text(Display.progressText(p)))
                    .setTitle(text(Display.truncate(p.label.ifEmpty { feed.title }, Display.MAX_LABEL)))
                    .setTapAction(tap)
                    .build()
            }
            else -> null
        }
    }

    private fun errorData(type: ComplicationType, state: FeedState, tap: PendingIntent): ComplicationData {
        val short = when (state) {
            FeedState.Loading -> "…"
            FeedState.Unreachable -> "Offline"
            is FeedState.Malformed -> "Bad"
            FeedState.Unsupported -> "Update"
            else -> "?"
        }
        val long = Display.stateHeadline(state) ?: short
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(short), text(long)).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text(long), text(long)).setTapAction(tap).build()
            else -> NoDataComplicationData()
        }
    }

    companion object {
        private val PREVIEW = Feed(
            title = "JARVIS",
            updatedEpochMs = 0L,
            kind = Kind.STATS,
            rows = listOf(Row("Apps", "1/5", null), Row("Asks", "2", null)),
            progress = Progress("Apps", 1.0, 5.0),
        )
    }
}
