package io.github.larmstrong1127.wristfeed.tile

import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.larmstrong1127.wristfeed.core.FeedState
import io.github.larmstrong1127.wristfeed.core.StateMachine
import io.github.larmstrong1127.wristfeed.data.FeedStore
import io.github.larmstrong1127.wristfeed.data.RefreshWorker
import io.github.larmstrong1127.wristfeed.ui.MainActivity

/**
 * One tile showing the chosen feed (the first feed unless "Show on tile" was picked in the app).
 * All work here is a SharedPreferences read plus layout building, so it answers synchronously
 * with an immediate future; network work goes to WorkManager.
 */
class FeedTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val store = FeedStore(this)
        val record = store.tileFeed()
        val now = System.currentTimeMillis()
        val state: FeedState = record?.state(now) ?: FeedState.Loading

        maybeRefresh(record?.lastAttemptMs, now, hasFeed = record != null)

        val layout = if (record == null) {
            TileLayouts.setupPrompt(packageName, MainActivity::class.java.name)
        } else {
            TileLayouts.root(state, packageName, MainActivity::class.java.name)
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Re-render each minute so the footer age stays true without any network work.
            .setFreshnessIntervalMillis(60_000L)
            // VERIFY: tiles 1.4 Tile.Builder.setTileTimeline takes androidx.wear.protolayout.TimelineBuilders.Timeline.
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return Futures.immediateFuture(tile)
    }

    // VERIFY: tiles 1.4 name/signature; older tiles (<1.2) used onResourcesRequest with androidx.wear.tiles.ResourceBuilders.
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    /** SPEC Refresh: when the tile becomes visible, refresh if the last fetch is > 15 minutes old. */
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        val record = FeedStore(this).tileFeed() ?: return
        maybeRefresh(record.lastAttemptMs, System.currentTimeMillis(), hasFeed = true)
    }

    private fun maybeRefresh(lastAttemptMs: Long?, now: Long, hasFeed: Boolean) {
        if (hasFeed && StateMachine.shouldRefreshOnVisible(lastAttemptMs, now)) RefreshWorker.refreshNow(this)
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
    }
}
