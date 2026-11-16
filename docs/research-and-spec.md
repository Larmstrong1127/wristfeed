# WristFeed (working name was WristFeed): prior art and spec

*Researched 2026-11-15 by GitHub topic and name search, plus a read-only review of two cloned repos. Nothing downloaded was built or run.*

## Verdict: build it

No open-source project turns **any JSON link** into a Wear OS tile and complication. Everything found is either:
- **single-purpose:** one data source, hard-wired;
- **platform-bound:** Home Assistant or openHAB;
- **a library:** you still write the app.

The gap is narrow but real. The search covered GitHub topics `wear-os-tiles`, `wear-tiles`, `wearos`/`wear-os` combined with tile, complication, json and http, plus name searches. It could miss projects without topics or with other wording, so the README should invite "tell us if this exists."

## What exists

| Project | License | What it does | Relevance |
|---|---|---|---|
| [Whyater/Wrist-Usage](https://github.com/Whyater/Wrist-Usage) | MIT, 1★, Oct 2026 | Claude/Codex usage limits on a Galaxy Watch tile and complication. A Mac collector writes a **secret GitHub gist**; a phone app fetches it and relays it to the watch. | **Closest.** The same architecture, single-purpose. WristFeed is its generalization. |
| [halilozel1903/compose-wear-tiles](https://github.com/halilozel1903/compose-wear-tiles) | MIT, 0★, one push in Oct 2026, on JitPack | A tile library: `SimpleTileService`, `StatsTile`, `ProgressTile`, `MessageTile`, `RefreshPolicy`. | Layout ideas. Too young to depend on yet. |
| [amoledwatchfaces/Complications-Suite](https://github.com/amoledwatchfaces/Complications-Suite-Wear-OS) | **GPL-3.0**, 133★ | 42 complications, including Custom text, Custom goal, Countdown, BTC price. | Ideas only (GPL, so no code copied into an MIT project). |
| [Bocskay-Botond/bambu-watch](https://github.com/Bocskay-Botond/bambu-watch) | MIT | A 3D printer's live state on a tile, sent phone to watch over the Data Layer. | A good example of a state enum (printing, done, failed, paused, idle). |
| WearDash, openHAB for Wear OS | AGPL / EPL | Smart-home dashboards. | Platform-bound; different job. |
| Single-purpose tiles: CgmGlance, AqiWatchTile, WearCountdown, waterlogged, world-clock | various | One data source each. | They show demand for "one number on my wrist." |

## Ideas to absorb (credited)

1. **A secret GitHub gist as the transport** (Wrist-Usage): no server, free, and a script updates it with `gh`. Their README is honest that a secret gist is *unlisted, not private*, so feeds must hold no secrets. We copy that warning.
2. **Clock-skew guard** (Wrist-Usage `Snapshot.withSaneClock`): if the publisher's clock runs ahead, trust the download time, so stale data never looks fresh.
3. **Always show the data's real age** on the tile footer (Wrist-Usage): the gist cache plus Android background limits mean 5-15 minutes of lag.
4. **Explicit error states** (Wrist-Usage `FetchError`, compose-wear-tiles `MessageTile`): never a blank tile. Show "can't reach feed", "feed is old" or "feed is malformed".
5. **A layout chosen by the data** (compose-wear-tiles): a feed declares `kind: stats | progress | message`, and the app picks the matching layout.
6. **Complication types** (Complications Suite, ideas only): map the feed to `SHORT_TEXT`, `LONG_TEXT` and `RANGED_VALUE`. A progress value like Apps 1/5 becomes a ring on the watch face.
7. **A state enum with colors** (bambu-watch): `ok | warn | bad | idle`.

## Where WristFeed differs on purpose

- **Generic:** one tiny JSON format; any script, CI job or server can publish it.
- **Watch-only first:** the watch fetches the link itself (Galaxy Watch reaches the internet through the phone's Bluetooth or over Wi-Fi), so there's **one app to install, not two**. Wrist-Usage needed a matched phone app and same-key signing. **Risk to test early:** background fetch limits on the watch. A phone relay is the fallback.
- **Several feeds:** one tile per feed (JARVIS, CI, a server).
- **A publisher kit:** `wristfeed publish` (Python CLI) and a GitHub Action step, each one line.

## Feed format v1 (draft)

```json
{
  "wristfeed": 1,
  "title": "JARVIS",
  "status": "warn",
  "kind": "stats",
  "rows": [
    {"label": "Apps", "value": "1/5", "status": "bad"},
    {"label": "Asks", "value": "2"},
    {"label": "Systems", "value": "OK", "status": "ok"}
  ],
  "progress": {"label": "Apps", "value": 1, "max": 5},
  "updated": "2026-11-16T06:40:00Z",
  "tap": "https://claude.ai/artifact/4kiQJEi4RceupRhbVUZqDf"
}
```

Rules:
- Unknown fields are ignored.
- A feed older than `stale_after` (default 6h) shows "old".
- Text is truncated to what fits.

## Plan

1. A Python publisher, the format spec and its JSON Schema, and tests. No Android needed.
2. The Wear OS app (Kotlin, ProtoLayout): one tile, a short-text and ranged complication, a feed-URL setup screen, WorkManager refresh, error states.
3. The JARVIS feed: `jarvis_wrist.py` also writes `wristfeed.json` to a secret gist.
4. Test on Landon's Galaxy Watch (needs Android Studio and watch developer mode).
5. Publish only with Landon's go-ahead: MIT license, README with the credits above, CI, screenshots.
