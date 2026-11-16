package io.github.larmstrong1127.wristfeed.ui

import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// VERIFY: ScalingLazyColumn lives in wear compose-foundation .lazy since 1.2; with compose-material 1.3.1 the
// material copy is deprecated. Swap the import if Studio flags it.
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.remote.interactions.RemoteActivityHelper
import io.github.larmstrong1127.wristfeed.core.Display
import io.github.larmstrong1127.wristfeed.core.FeedState
import io.github.larmstrong1127.wristfeed.core.name
import io.github.larmstrong1127.wristfeed.data.AddResult
import io.github.larmstrong1127.wristfeed.data.FeedRecord
import io.github.larmstrong1127.wristfeed.data.FeedStore
import io.github.larmstrong1127.wristfeed.data.Refresher
import io.github.larmstrong1127.wristfeed.tile.TileLayouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feed list: add by URL (RemoteInput keyboard/voice), delete, choose the tile feed, refresh now. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTapUrl(intent)
        setContent { MaterialTheme { FeedListScreen(FeedStore(this)) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTapUrl(intent)
    }

    /**
     * The tile's tap carries the feed's tap_url; open it on the paired phone. Without a phone (or
     * without the companion side) the future fails and nothing else happens, as SPEC says.
     */
    private fun handleTapUrl(intent: Intent?) {
        val url = intent?.getStringExtra(TileLayouts.EXTRA_TAP_URL) ?: return
        intent.removeExtra(TileLayouts.EXTRA_TAP_URL)
        val target = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        // VERIFY: RemoteActivityHelper(context, executor = default) + startRemoteActivity(intent, nodeId = null)
        // returning ListenableFuture<Void> is the wear-remote-interactions 1.0.0 API.
        RemoteActivityHelper(this).startRemoteActivity(target, null)
        Toast.makeText(this, "Opening on phone", Toast.LENGTH_SHORT).show()
    }
}

private const val KEY_URL = "feed_url"

@Composable
fun FeedListScreen(store: FeedStore) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf(store.all()) }
    var tileId by remember { mutableStateOf(store.tileFeedId()) }
    var tick by remember { mutableStateOf(0) }

    fun reload() {
        feeds = store.all()
        tileId = store.tileFeedId()
    }

    // Poll the store while visible so background refreshes and ages show up.
    LaunchedEffect(tick) {
        while (true) {
            reload()
            delay(3_000)
        }
    }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val url = RemoteInput.getResultsFromIntent(data)?.getCharSequence(KEY_URL)?.toString()?.trim()
        if (url.isNullOrEmpty()) return@rememberLauncherForActivityResult
        // Store writes (commit()) run on IO, never on Main.
        scope.launch {
            when (val added = withContext(Dispatchers.IO) { store.add(url) }) {
                is AddResult.Rejected -> Toast.makeText(context, added.message, Toast.LENGTH_SHORT).show()
                is AddResult.Added -> {
                    reload()
                    Refresher.refreshOne(context, store, added.id)
                    reload()
                }
            }
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text("WristFeed", style = MaterialTheme.typography.title3) }

        if (feeds.isEmpty()) {
            item { Text("No feeds yet", style = MaterialTheme.typography.caption2) }
        }

        feeds.forEach { rec ->
            item { FeedChip(rec, isTile = rec.id == tileId) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rec.id != tileId) {
                        CompactChip(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { store.setTileFeed(rec.id) }
                                    reload()
                                    Refresher.notifySurfaces(context)
                                }
                            },
                            label = { Text("On tile") },
                        )
                    }
                    CompactChip(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.remove(rec.id) }
                                reload()
                                Refresher.notifySurfaces(context)
                            }
                        },
                        label = { Text("Delete") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }

        if (feeds.size < FeedStore.MAX_FEEDS) {
            item {
                Chip(
                    onClick = {
                        // VERIFY: wear-input 1.1.0 RemoteInputIntentHelper.createActionRemoteInputIntent / putRemoteInputsExtra.
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        val input = RemoteInput.Builder(KEY_URL).setLabel("Feed URL (https://…)").build()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
                        addLauncher.launch(intent)
                    },
                    label = { Text("Add feed") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (feeds.isNotEmpty()) {
            item {
                Chip(
                    onClick = {
                        // Inline while the app is open (WorkManager handles background refresh).
                        scope.launch {
                            Refresher.refreshAll(context)
                            tick++
                        }
                    },
                    label = { Text("Refresh now") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FeedChip(rec: FeedRecord, isTile: Boolean) {
    val state = rec.state()
    val title = when (state) {
        is FeedState.Fresh -> Display.title(state.feed)
        is FeedState.Stale -> Display.title(state.feed)
        else -> rec.url.removePrefix("https://")
    }
    val detail = Display.footer(state) ?: Display.stateHeadline(state) ?: state.name()
    val dotColor = when (state) {
        is FeedState.Fresh -> Color(TileLayouts.statusColor(state.feed.status))
        is FeedState.Stale -> Color(TileLayouts.statusColor(state.feed.status)).copy(alpha = 0.5f)
        FeedState.Loading -> Color(0xFF9E9E9E)
        else -> Color(0xFFE53935)
    }
    Chip(
        onClick = {},
        label = { Text((if (isTile) "▣ " else "") + title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(detail, color = dotColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}
