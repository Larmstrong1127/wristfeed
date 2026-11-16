# WristFeed feed format, version 1

This is the contract between anything that **publishes** a feed and the watch app that **shows** it. Both sides follow this file and nothing else. Change it only by bumping `wristfeed` (the version field).

## Transport

A feed is one JSON document served over HTTPS at a stable URL, for example a raw secret GitHub Gist, a GitHub Pages file or any static host. The watch fetches it with a plain GET, no auth. **A feed must never contain secrets:** a secret gist is unlisted, not private (credit: Wrist-Usage's README).

## Document

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
  "message": null,
  "updated": "2026-11-16T06:40:00Z",
  "stale_after_minutes": 360,
  "tap_url": "https://example.com/dashboard"
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `wristfeed` | integer | yes | Format version. The app shows "unsupported feed version" for any value it doesn't know (v1 app: only `1`). |
| `title` | string | yes | Shown at the top of the tile. The app shows at most 24 characters, cut with "…". |
| `updated` | string, RFC 3339 UTC (`Z` or offset) | yes | When the publisher made the data. |
| `status` | `"ok" \| "warn" \| "bad" \| "idle"` | no, default `"idle"` | The overall state. It colors the tile's accent: ok green, warn amber, bad red, idle grey. |
| `kind` | `"stats" \| "progress" \| "message"` | no, default `"stats"` | Which layout to use. |
| `rows` | array of `{label, value, status?}` | for `stats` | Up to 3 rows are shown (the rest ignored). label ≤ 12 characters and value ≤ 10 characters shown, cut with "…". A row's `status` colors its value. |
| `progress` | `{label, value, max}` with numbers, `max` > 0 | for `progress` | A ring with "value/max". Also feeds the RANGED_VALUE complication. Value is clamped to 0..max. |
| `message` | string or null | for `message` | One short line (≤ 60 characters shown), for "all quiet"-type feeds. |
| `stale_after_minutes` | integer ≥ 1 | no, default 360 | After this long since `updated`, the tile shows "Old data". |
| `tap_url` | string (https URL) or null | no | Tapping the tile opens it on the phone (via the companion's remote-open intent if available; otherwise nothing happens). |

Rules:
1. **Unknown fields are ignored**, so feeds can carry extra data.
2. **Missing optional fields use their defaults.** A missing required field makes the feed **malformed**.
3. **Wrong-typed or out-of-range values** (an unknown `status`, a non-number progress value, `max` ≤ 0) make that part fall back to its default; only a broken required field makes the whole feed malformed.
4. **Clock-skew guard** (credit: Wrist-Usage): if `updated` is more than 2 minutes after the time the watch downloaded the feed, the app uses the download time instead.
5. **Size:** the app reads at most 64 KB; anything larger is malformed.

## Watch states

The tile always shows exactly one of these:

| State | When | Tile shows |
|---|---|---|
| `loading` | No fetch has finished since setup | "Loading…" |
| `fresh` | Last fetch parsed and `age < stale_after_minutes` | The layout, plus a footer "Nm ago" or "Nh ago" |
| `stale` | Parsed, but `age ≥ stale_after_minutes` | The layout dimmed, footer "Old data · " followed by the normal age label ("20m ago" or "7h ago") |
| `unreachable` | The last fetch failed (network, HTTP ≠ 200) **and** there's no earlier good copy | "Can't reach feed" |
| `malformed` | The body isn't a valid v1 feed **and** there's no earlier good copy | "Bad feed" plus a short reason |
| `unsupported` | `wristfeed` isn't 1 | "Unsupported feed version" |

If a fetch fails but an earlier good copy exists, the app keeps showing that copy (aging naturally into `stale`), never a blank tile.

## Complications

| Type | Content |
|---|---|
| SHORT_TEXT | The first row's value (stats), "value/max" (progress) or the title (message); title as the label. |
| LONG_TEXT | "title: row1 · row2" or the message. |
| RANGED_VALUE | `progress` when present; otherwise not offered. |

## Refresh

The app asks Android to refresh every 30 minutes (WorkManager periodic work, network required), and again when the tile becomes visible if the last fetch is more than 15 minutes old. Android may delay background work; the footer age always tells the truth.

## Publisher CLI (reference implementation in `publisher/`)

- `wristfeed validate FILE`: exits 0 on a valid v1 feed, otherwise prints each problem and exits 1.
- `wristfeed build --title T [--status S] [--kind K] [--row "Label=Value[:status]"]... [--progress "Label=V/MAX"] [--message M] [--stale-after N] [--tap-url U] [--out FILE]`: writes a valid feed with `updated` set to now.
- `wristfeed publish FILE --gist ID`: updates an existing gist's file through `gh` (never creates one silently; `--create` must be explicit and prints the raw URL).
