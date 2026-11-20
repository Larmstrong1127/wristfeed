"""wristfeed preview: SVG of the round tile, mirroring TileLayouts.kt."""

from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

import pytest

from wristfeed.cli import main
from wristfeed.feed import parse, parse_bytes
from wristfeed.preview import STATES, dim, render

EXAMPLES = Path(__file__).resolve().parents[2] / "examples"
GOLDEN = Path(__file__).resolve().parent / "golden"
NOW = datetime(2026, 11, 16, 6, 45, tzinfo=timezone.utc)
SVG_NS = "{http://www.w3.org/2000/svg}"
NOW_ISO = "2026-11-16T06:45:00Z"
BASE = {"wristfeed": 1, "title": "T", "updated": "2026-11-16T06:40:00Z"}


def texts(svg: str) -> list[str]:
    root = ET.fromstring(svg)
    return [t.text or "" for t in root.iter(f"{SVG_NS}text")]


def fills(svg: str) -> set[str]:
    root = ET.fromstring(svg)
    return {e.get("fill") for e in root.iter() if e.get("fill")} | {
        e.get("stroke") for e in root.iter() if e.get("stroke")
    }


def svg_for(doc: dict, state: str | None = None, now: datetime = NOW) -> str:
    return render(parse(doc), now, state=state)


@pytest.mark.parametrize(
    "extra",
    [
        {"kind": "stats", "rows": [{"label": "A", "value": "1", "status": "ok"}]},
        {"kind": "stats"},
        {"kind": "progress", "progress": {"label": "P", "value": 1, "max": 4}},
        {"kind": "progress"},
        {"kind": "message", "message": "hello"},
    ],
)
def test_each_kind_renders_valid_xml(extra: dict) -> None:
    svg = svg_for({**BASE, **extra})
    root = ET.fromstring(svg)
    assert root.tag == f"{SVG_NS}svg"
    assert "5m ago" in texts(svg)


@pytest.mark.parametrize("state", STATES)
def test_each_state_renders(state: str) -> None:
    svg = svg_for({**BASE, "message": "m", "kind": "message"}, state=state)
    ET.fromstring(svg)
    shown = texts(svg) + [" ".join(texts(svg))]  # long headlines wrap to 2 lines
    expected = {
        "fresh": "5m ago",
        "stale": "Old data · 5m ago",
        "unreachable": "Can't reach feed",
        "malformed": "Bad feed",
        "unsupported": "Unsupported feed version",
        "loading": "Loading…",
    }[state]
    assert expected in shown


def test_auto_states_from_document() -> None:
    assert "Bad feed" in texts(render(parse_bytes(b"[]"), NOW))
    unsupported = render(parse({**BASE, "wristfeed": 2}), NOW)
    assert " ".join(texts(unsupported)) == "Unsupported feed version"
    reason = texts(render(parse({"wristfeed": 1}), NOW))
    assert len(reason) == 2 and len(reason[1]) <= 40


def test_truncated_text_appears() -> None:
    doc = {
        **BASE,
        "title": "A very long title that exceeds the limit",
        "rows": [{"label": "LabelTooLongHere", "value": "ValueTooLong!"}] * 5,
    }
    shown = texts(svg_for(doc))
    assert "A very long title that …" in shown
    assert "LabelTooLon…" in shown
    assert "ValueTooL…" in shown
    assert shown.count("LabelTooLon…") == 3  # MAX_ROWS


def test_stale_uses_dimmed_colors() -> None:
    doc = {**BASE, "status": "ok", "rows": [{"label": "A", "value": "1", "status": "bad"}]}
    fresh = fills(svg_for(doc))
    stale_now = datetime(2026, 11, 16, 13, 0, tzinfo=timezone.utc)
    stale = svg_for(doc, now=stale_now)
    assert {"#4CAF50", "#E53935", "#BDBDBD"} <= fresh
    assert {"#265728", "#721C1A", "#5E5E5E"} <= fills(stale)
    assert "#4CAF50" not in fills(stale)
    assert any(t.startswith("Old data") for t in texts(stale))
    assert dim(0xFFFFFF, True) == 0x7F7F7F


def test_progress_ring_sweep() -> None:
    svg = svg_for({**BASE, "kind": "progress", "progress": {"label": "", "value": 0, "max": 5}})
    root = ET.fromstring(svg)
    assert not list(root.iter(f"{SVG_NS}path"))  # sweep 0 draws only the track
    assert "0/5" in texts(svg)


def test_deterministic() -> None:
    doc = json.loads((EXAMPLES / "ci-status.json").read_text(encoding="utf-8"))
    assert svg_for(doc) == svg_for(doc)


@pytest.mark.parametrize("name", ["ci-status", "job-search", "server-health", "dashboard"])
def test_golden(name: str, tmp_path: Path) -> None:
    out = tmp_path / "out.svg"
    rc = main(["preview", str(EXAMPLES / f"{name}.json"), "--out", str(out), "--now", NOW_ISO])
    assert rc == 0
    golden = (GOLDEN / f"{name}.svg").read_bytes().replace(b"\r\n", b"\n")  # autocrlf-safe
    assert out.read_bytes() == golden


def test_cli_errors(tmp_path: Path) -> None:
    f = tmp_path / "f.json"
    f.write_text("[]", encoding="utf-8")
    out = str(tmp_path / "o.svg")
    assert main(["preview", str(f), "--out", out, "--now", "nope"]) == 2
    assert main(["preview", str(f), "--out", out, "--state", "fresh"]) == 2
    assert main(["preview", str(f), "--out", out, "--size", "300"]) == 0
    assert 'width="300"' in Path(out).read_text(encoding="utf-8")


@pytest.mark.parametrize("title", ["a\u0001b", "a\u000bb", "a￾b", "<script>alert(1)</script>&\"'"])
def test_feed_text_never_breaks_the_svg(title) -> None:
    # Control characters and U+FFFE are illegal in XML 1.0 (second review, 2026-11-15).
    import json as _json
    from xml.dom import minidom

    from wristfeed.feed import parse_bytes
    from wristfeed.preview import render

    doc = {"wristfeed": 1, "title": title, "updated": "2026-11-16T06:40:00Z"}
    res = parse_bytes(_json.dumps(doc).encode())
    svg = render(res, now=datetime(2026, 11, 16, 6, 45, tzinfo=timezone.utc))
    minidom.parseString(svg)  # raises if not well-formed
    assert "<script" not in svg
