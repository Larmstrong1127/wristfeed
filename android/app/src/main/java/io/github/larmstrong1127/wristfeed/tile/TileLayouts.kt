package io.github.larmstrong1127.wristfeed.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.em
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

    // Dashboard (preview.py mirrors these numbers).
    private const val DASH_TRACK = 0xFF262626.toInt()
    private const val DASH_MUTED = 0xFF9E9E9E.toInt()
    private const val DASH_BEZEL_DP = 5f
    private const val DASH_BEZEL_INSET_DP = 4f
    private const val DASH_CHIP_DP = 56f
    private const val DASH_HEADLINE_DP = 172f

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
            is FeedState.Fresh -> if (state.feed.kind == Kind.DASHBOARD) dashboard(state.feed, Display.footer(state)!!, dimmed = false)
                else feedColumn(state.feed, Display.footer(state)!!, dimmed = false)
            is FeedState.Stale -> if (state.feed.kind == Kind.DASHBOARD) dashboard(state.feed, Display.footer(state)!!, dimmed = true)
                else feedColumn(state.feed, Display.footer(state)!!, dimmed = true)
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

    private fun text(
        s: String, sizeSp: Float, color: Int, bold: Boolean = false, maxLines: Int = 1, spacingEm: Float = 0f,
    ): LayoutElement {
        val style = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setColor(argb(color))
        if (bold) style.setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
        if (spacingEm != 0f) style.setLetterSpacing(em(spacingEm))
        // VERIFY: Text.Builder.setText(String) and setMultilineAlignment(Int) overloads in protolayout 1.2.0.
        return LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(style.build())
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE) // never clip silently
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
            Kind.DASHBOARD -> {} // root() routes dashboards to dashboard(); unreachable here
        }

        col.addContent(spacer(6f))
        col.addContent(text(footer, 11f, dim(GREY_TEXT, dimmed)))
        return col.build()
    }

    /**
     * Dashboard (SPEC "Dashboard"): progress as a bezel ring around the screen edge, then a centered
     * stack: eyebrow (title, small caps), headline (2 lines), alert (colored dot line), up to 3
     * chips (value over label), footer. The headline sits in a fixed-width box so it wraps inside
     * the round screen.
     */
    private fun dashboard(feed: Feed, footer: String, dimmed: Boolean): LayoutElement {
        val accent = dim(statusColor(feed.status), dimmed)
        val body = dim(WHITE, dimmed)
        val muted = dim(DASH_MUTED, dimmed)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(Display.title(feed).uppercase(), 11f, muted, bold = true, spacingEm = 0.12f))
            .addContent(spacer(4f))
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(dp(DASH_HEADLINE_DP))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(Display.headline(feed) ?: Display.title(feed), 17f, body, bold = true, maxLines = 2))
                    .build()
            )

        Display.alert(feed)?.let { a ->
            col.addContent(spacer(6f))
            col.addContent(text("● ${a.text}", 12f, dim(statusColor(a.status), dimmed)))
        }

        val chips = Display.chips(feed)
        if (chips.isNotEmpty()) {
            val row = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
            chips.forEach { c ->
                row.addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(dp(DASH_CHIP_DP))
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(text(c.value, 16f, if (c.status != null) dim(statusColor(c.status), dimmed) else body, bold = true))
                        .addContent(text(c.label.uppercase(), 9f, muted, spacingEm = 0.08f))
                        .build()
                )
            }
            col.addContent(spacer(10f)).addContent(row.build())
        }
        col.addContent(spacer(6f)).addContent(text(footer, 10f, muted))

        val screen = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHeight(expand())
        feed.progress?.let { p ->
            fun arc(lengthDeg: Float, color: Int) = LayoutElementBuilders.Arc.Builder()
                .setAnchorAngle(degrees(0f))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(
                    LayoutElementBuilders.ArcLine.Builder()
                        .setLength(degrees(lengthDeg))
                        .setThickness(dp(DASH_BEZEL_DP))
                        .setColor(argb(color))
                        .build()
                )
                .build()
            val bezel = LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(DASH_BEZEL_INSET_DP)).build())
                        .build()
                )
                .addContent(arc(360f, dim(DASH_TRACK, dimmed)))
            val sweep = (p.fraction.coerceIn(0.0, 1.0) * 360.0).toFloat()
            if (sweep > 0f) bezel.addContent(arc(sweep, accent))
            screen.addContent(bezel.build())
        }
        screen.addContent(col.build())
        return screen.build()
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
