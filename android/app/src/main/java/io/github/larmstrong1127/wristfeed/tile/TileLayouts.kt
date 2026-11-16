package io.github.larmstrong1127.wristfeed.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import io.github.larmstrong1127.wristfeed.core.Display
import io.github.larmstrong1127.wristfeed.core.Feed
import io.github.larmstrong1127.wristfeed.core.FeedState
import io.github.larmstrong1127.wristfeed.core.Kind
import io.github.larmstrong1127.wristfeed.core.Status

/**
 * Builds the tile from a [FeedState] with plain ProtoLayout elements (no protolayout-material),
 * which keeps the API surface small and stable. Layout idea (stats / progress / message chosen by
 * the data) credit: halilozel1903/compose-wear-tiles; no code taken.
 */
object TileLayouts {
    const val EXTRA_TAP_URL = "wristfeed.tap_url"
    const val CLICK_ID = "open"

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREY_TEXT = 0xFFBDBDBD.toInt()
    private const val TRACK = 0xFF303030.toInt()

    fun statusColor(s: Status?): Int = when (s) {
        Status.OK -> 0xFF4CAF50.toInt()   // green
        Status.WARN -> 0xFFFFB300.toInt() // amber
        Status.BAD -> 0xFFE53935.toInt()  // red
        Status.IDLE, null -> 0xFF9E9E9E.toInt() // grey
    }

    /** Stale = dimmed: scale RGB to 50% (alpha stays opaque so it renders the same everywhere). */
    private fun dim(c: Int, dimmed: Boolean): Int {
        if (!dimmed) return c
        val r = ((c shr 16) and 0xFF) / 2
        val g = ((c shr 8) and 0xFF) / 2
        val b = (c and 0xFF) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun root(state: FeedState, packageName: String, activityClass: String): LayoutElement {
        val tapUrl = when (state) {
            is FeedState.Fresh -> state.feed.tapUrl
            is FeedState.Stale -> state.feed.tapUrl
            else -> null
        }
        val content: LayoutElement = when (state) {
            is FeedState.Fresh -> feedColumn(state.feed, Display.footer(state)!!, dimmed = false)
            is FeedState.Stale -> feedColumn(state.feed, Display.footer(state)!!, dimmed = true)
            is FeedState.Malformed -> messageColumn(Display.stateHeadline(state)!!, Display.truncate(state.reason, 40))
            else -> messageColumn(Display.stateHeadline(state)!!, null)
        }
        return wrap(content, tapUrl, packageName, activityClass)
    }

    /** Shown when no feed is configured yet. */
    fun setupPrompt(packageName: String, activityClass: String): LayoutElement =
        wrap(messageColumn("WristFeed", "Tap to add a feed"), null, packageName, activityClass)

    /** Full-screen box; tapping anywhere opens the app (which forwards tap_url to the phone). */
    private fun wrap(content: LayoutElement, tapUrl: String?, packageName: String, activityClass: String): LayoutElement {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(activityClass)
        if (tapUrl != null) {
            activity.addKeyToExtraMapping(
                EXTRA_TAP_URL,
                ActionBuilders.AndroidStringExtra.Builder().setValue(tapUrl).build()
            )
        }
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_ID)
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build())
            .addContent(content)
            .build()
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false, maxLines: Int = 1): LayoutElement {
        val style = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setColor(argb(color))
        if (bold) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        // VERIFY: Text.Builder.setText(String) and setMultilineAlignment(Int) overloads in protolayout 1.2.0.
        return LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(style.build())
            .setMaxLines(maxLines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .build()
    }

    private fun spacer(h: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    private fun messageColumn(headline: String, detail: String?): LayoutElement {
        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(headline, 16f, WHITE, bold = true, maxLines = 2))
        if (detail != null) col.addContent(spacer(4f)).addContent(text(detail, 12f, GREY_TEXT, maxLines = 2))
        return col.build()
    }

    private fun feedColumn(feed: Feed, footer: String, dimmed: Boolean): LayoutElement {
        val accent = dim(statusColor(feed.status), dimmed)
        val body = dim(WHITE, dimmed)
        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(Display.title(feed), 15f, accent, bold = true))
            .addContent(spacer(6f))

        when (feed.kind) {
            Kind.STATS -> {
                val rows = Display.shownRows(feed)
                if (rows.isEmpty()) col.addContent(text("No rows", 13f, dim(GREY_TEXT, dimmed)))
                rows.forEach { r ->
                    col.addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(text(r.label, 13f, dim(GREY_TEXT, dimmed)))
                            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                            .addContent(text(r.value, 14f, if (r.status != null) dim(statusColor(r.status), dimmed) else body, bold = true))
                            .build()
                    )
                }
            }
            Kind.PROGRESS -> {
                val p = feed.progress
                if (p == null) col.addContent(text("No progress", 13f, dim(GREY_TEXT, dimmed)))
                else col.addContent(ring(p.fraction, Display.progressText(p), Display.truncate(p.label, Display.MAX_LABEL), accent, body, dimmed))
            }
            Kind.MESSAGE -> col.addContent(text(Display.message(feed) ?: "", 13f, body, maxLines = 3))
        }

        col.addContent(spacer(6f))
        col.addContent(text(footer, 11f, dim(GREY_TEXT, dimmed)))
        return col.build()
    }

    /** A small progress ring: grey track arc + accent arc, centered "value/max" and label. */
    private fun ring(fraction: Double, center: String, label: String, accent: Int, body: Int, dimmed: Boolean): LayoutElement {
        val size = 72f
        fun arc(lengthDeg: Float, color: Int) = LayoutElementBuilders.Arc.Builder()
            .setAnchorAngle(degrees(0f))
            .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
            .addContent(
                LayoutElementBuilders.ArcLine.Builder()
                    .setLength(degrees(lengthDeg))
                    .setThickness(dp(6f))
                    .setColor(argb(color))
                    .build()
            )
            .build()

        // VERIFY (visual only): an Arc inside a fixed dp Box draws along that box's edge; check ring size on a 1.2" screen.
        val sweep = (fraction.coerceIn(0.0, 1.0) * 360.0).toFloat()
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(arc(360f, TRACK))
        if (sweep > 0f) box.addContent(arc(sweep, accent))
        box.addContent(
            LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(center, 14f, body, bold = true))
                .apply { if (label.isNotEmpty()) addContent(text(label, 10f, dim(GREY_TEXT, dimmed))) }
                .build()
        )
        return box.build()
    }
}
