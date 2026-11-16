# WristFeed conformance corpus

Shared test cases for every WristFeed v1 parser (the Python publisher in `publisher/` and the Kotlin watch app). Each case is a pair:

- `<name>.json`: the raw feed body, exactly as the watch would download it (read as bytes; some files are deliberately not valid JSON).
- `<name>.expected.json`: the expected parse outcome.

File names start with the outcome family: `valid-`, `fallback-` (usable, but some field fell back to its default), `malformed-`, `unsupported-`.

## Expected format

```json
{
  "outcome": "ok",
  "fallback": false,
  "feed": { "...normalized feed..." }
}
```

| Key | Meaning |
|---|---|
| `outcome` | `"ok"`, `"malformed"` or `"unsupported"`. `ok` maps to the watch states `fresh`/`stale`, which depend on the clock and are not part of the corpus. |
| `fallback` | `true` when at least one optional field or row was wrong and fell back to its default (SPEC rule 3). A strict validator (`wristfeed validate`) rejects these. |
| `feed` | Present only when `outcome` is `ok`. Every field, with defaults filled in: `wristfeed`, `title` (untruncated), `updated` (normalized to UTC, whole seconds, `Z` suffix), `status`, `kind`, `rows` (all valid rows in order, each with `label`, `value`, `status` or null; bad rows dropped; display shows the first 3), `progress` (null or `{label, value, max}` with `value` already clamped to 0..max; compare numbers numerically), `message`, `stale_after_minutes`, `tap_url`. |

Display truncation, the clock-skew guard and fresh/stale are covered by unit tests on each side (see `publisher/tests/test_display.py` for the reference cases).

## Adding a case

Add `<name>.json` and a hand-checked `<name>.expected.json`. Both test suites pick up every pair automatically.
