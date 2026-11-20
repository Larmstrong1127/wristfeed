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
| `wristfeed` | integer | yes | Format version; must be an integer in -2^53..2^53 (anything else, including 1.0 or "1", is malformed). The app shows "unsupported feed version" for any value it doesn't know (v1 app: only `1`). |
| `title` | string | yes | Shown at the top of the tile. The app shows at most 24 characters, cut with "…". |
| `updated` | string, RFC 3339 UTC (`Z` or offset) | yes | When the publisher made the data. Form: `YYYY-MM-DDTHH:MM:SS`, optional `.` plus 1 to 9 fraction digits, then `Z` or `±HH:MM` with minutes < 60 and the offset at most 18:00. Anything else is malformed. |
| `status` | `"ok" \| "warn" \| "bad" \| "idle"` | no, default `"idle"` | The overall state. It colors the tile's accent: ok green, warn amber, bad red, idle grey. |
| `kind` | `"stats" \| "progress" \| "message" \| "dashboard"` | no, default `"stats"` | Which layout to use. `dashboard` was added within v1 (see "Dashboard"); a v1 app that predates it treats it as an unknown value (rule 3) and shows `stats`. |
| `rows` | array of `{label, value, status?}` | for `stats` | Up to 3 rows are shown (the rest ignored). label ≤ 12 characters and value ≤ 10 characters shown, cut with "…". A row's `status` colors its value. |
| `progress` | `{label, value, max}` with numbers, `max` > 0 | for `progress` | A ring with "value/max". Also feeds the RANGED_VALUE complication. Value is clamped to 0..max. |
| `message` | string or null | for `message` | One short line (≤ 60 characters shown), for "all quiet"-type feeds. |
| `headline` | string or null | for `dashboard` | The one thing to know or do next, shown large (≤ 44 characters shown, up to 2 lines). |
| `alert` | `{text, status?}` or null | no | One line that needs attention (≤ 32 characters shown), colored by its `status` (default `"warn"`). Shown only by `dashboard`; omit or null when there is nothing to flag. |
| `stale_after_minutes` | integer 1..525600 (one year) | no, default 360; out of range or not an integer falls back to 360 | After this long since `updated`, the tile shows "Old data". |
| `tap_url` | string (https URL) or null | no | Tapping the tile opens it on the phone (via the companion's remote-open intent if available; otherwise nothing happens). |

Rules:
1. **Unknown fields are ignored**, so feeds can carry extra data.
2. **Missing optional fields use their defaults.** A missing required field makes the feed **malformed**.
3. **Wrong-typed or out-of-range values** (an unknown `status`, a non-number progress value, `max` ≤ 0) make that part fall back to its default; only a broken required field makes the whole feed malformed.
4. **Clock-skew guard** (credit: Wrist-Usage): if `updated` is more than 2 minutes after the time the watch downloaded the feed, the app uses the download time instead.
5. **Size:** the app reads at most 64 KB; anything larger is malformed.
6. **Strict JSON:** the body must be RFC 8259 JSON. `NaN`, `Infinity` and `-Infinity` literals make it malformed. JSON nesting deeper than 32 levels (the root object is level 1) is malformed.
7. **Numbers:** an integer literal that does not fit a signed 64-bit integer is read as a floating-point number. A number whose magnitude exceeds 1e308 (or overflows to infinity) is "not a number" for every field (so a progress `value` of `1e400` falls back).

## Dashboard

`kind: "dashboard"` is an at-a-glance layout for a round screen. It was added inside v1 because
rules 1 and 3 keep it compatible: an older v1 app ignores `headline` and `alert` and shows the
`stats` layout instead. From the outside in:

- **Bezel ring:** `progress` drawn around the screen edge (a track plus an accent arc in the
  feed's `status` color). With no `progress`, there is no ring.
- **Eyebrow:** `title` in small capitals, muted.
- **Headline:** `headline`, large, up to 2 lines.
- **Alert:** `alert.text` after a dot in `alert.status` color. Absent when `alert` is null.
- **Chips:** the first 3 `rows` side by side, value above label. In this layout a value shows
  ≤ 6 characters and a label ≤ 8, cut with "…". A row's `status` colors its value.
- **Footer:** the age, as in every layout.

Rules for `alert` (rule 3 applies): a non-object, or an object without a non-blank string `text`,
falls back to null. A missing `status` is `"warn"`, and a wrong one falls back to `"warn"`.
A non-string `headline` falls back to null.

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
| SHORT_TEXT | The first row's value (stats, dashboard), "value/max" (progress) or the title (message), cut to 7 characters; title as the label, cut to 7 characters (cut with "…"). |
| LONG_TEXT | "title: row1 · row2", the message, or for a dashboard the headline (the stats text when there is none). |
| RANGED_VALUE | `progress` when present; otherwise not offered. |

## Number display

Progress numbers are shown by one rule (vectors in `conformance/display/numbers.json`): a whole number with magnitude below 1e15 prints as a plain integer (`5`, `-3`, `0`). Any other number is rounded to 6 significant digits (round half up, applied to the exact binary value), trailing zeros removed, and printed in plain decimal notation, never with an exponent (`2.5`, `1234570`, `0.00000123457`).

## Refresh

The app asks Android to refresh every 30 minutes (WorkManager periodic work, network required), and again when the tile becomes visible if the last fetch is more than 15 minutes old. A manual or tile-triggered refresh is one-shot work with its own unique name and `KEEP` policy, so at most one one-shot refresh is queued at a time; it never replaces the periodic work. Android may delay background work; the footer age always tells the truth.

## Publisher CLI (reference implementation in `publisher/`)

- `wristfeed validate FILE`: exits 0 on a valid v1 feed, otherwise prints each problem and exits 1.
- `wristfeed build --title T [--status S] [--kind K] [--row "Label=Value[:status]"]... [--progress "Label=V/MAX"] [--message M] [--headline H] [--alert "Text[:status]"] [--stale-after N] [--tap-url U] [--out FILE]`: writes a valid feed with `updated` set to now.
- `wristfeed publish FILE --gist ID`: updates an existing gist's file through `gh` (never creates one silently; `--create` must be explicit, sends the file on stdin under `--filename`, and prints the raw URL when the gist owner is known, otherwise the gist URL with a hint to click Raw).
