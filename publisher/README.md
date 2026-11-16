# wristfeed (publisher)

Build, validate and publish [WristFeed v1](../SPEC.md) feeds: small JSON documents that the WristFeed Wear OS app shows as a watch tile. Pure Python 3.10+, no runtime dependencies. `wristfeed.feed` is also the reference parser that the watch app mirrors.

> **Warning: a secret gist is unlisted, not private.** Anyone who has the URL can read it, and URLs leak. Never put secrets, tokens or personal data in a feed.

## Install

```sh
pip install ./publisher            # or: pipx install ./publisher
pip install -e "./publisher[dev]"  # for development (pytest, jsonschema, ruff)
```

## Usage

```sh
# Write a feed (updated = now, UTC)
wristfeed build --title JARVIS --status warn \
  --row "Apps=1/5:bad" --row "Asks=2" --row "Systems=OK:ok" \
  --progress "Apps=1/5" --stale-after 360 --tap-url https://example.com --out feed.json

# Check a feed: exit 0 if valid, otherwise each problem is printed and exit 1
wristfeed validate feed.json

# Update a file in an existing gist through the GitHub CLI (gh must be logged in)
wristfeed publish feed.json --gist 0123abcd --filename jarvis.json

# Create a NEW secret gist (only with the explicit flag) and print its raw URL
wristfeed publish feed.json --create --filename jarvis.json
```

- `--row "Label=Value[:status]"` can repeat; the `:status` suffix is read only when it is `ok`, `warn`, `bad` or `idle`, so `Time=12:30` keeps its colon.
- `--progress "Label=V/MAX"` takes numbers, `MAX` > 0.
- `build` refuses to write a feed that `validate` would reject.
- The printed raw URL has no revision hash, so it always serves the newest version. Paste it into the watch app once.
- `publish` never creates a gist unless you pass `--create`.

`validate` is strict: a feed the watch could still show (because a bad optional field falls back to its default) is reported as a problem so you can fix it.

## GitHub Actions

```yaml
name: wristfeed
on:
  schedule: [{cron: "*/30 * * * *"}]
  workflow_dispatch:
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: {python-version: "3.12"}
      - run: pip install ./publisher
      - name: Build and publish the feed
        env:
          GH_TOKEN: ${{ secrets.GIST_TOKEN }}  # a token with the "gist" scope
        run: |
          wristfeed build --title "CI" --status ok \
            --row "Builds=12" --row "Failing=0:ok" --out feed.json
          wristfeed validate feed.json
          wristfeed publish feed.json --gist ${{ vars.WRISTFEED_GIST_ID }} --filename ci.json
```

## Development

```sh
cd publisher
PYTHONPATH=src python -m pytest -q
ruff check .
```

The shared conformance corpus lives in [`../conformance/`](../conformance/README.md); both the Python and Kotlin test suites run every case in it.

MIT licensed.
