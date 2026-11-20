"""Dashboard kind (SPEC "Dashboard"): display limits, the build options and the SVG preview."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone

from wristfeed.cli import build_feed
from wristfeed.feed import (
    ALERT_MAX_CHARS,
    HEADLINE_MAX_CHARS,
    display_alert,
    display_chips,
    display_headline,
    parse,
)
from wristfeed.preview import render

NOW = datetime(2026, 11, 16, 7, 0, tzinfo=timezone.utc)


def feed(**kw):
    doc = {"wristfeed": 1, "title": "Today · Thu", "updated": "2026-11-16T06:40:00Z",
           "kind": "dashboard", **kw}
    r = parse(doc)
    assert r.outcome == "ok"
    return r.feed


def test_headline_and_alert_limits() -> None:
    f = feed(headline="x" * 50, alert={"text": "y" * 40, "status": "bad"})
    assert len(display_headline(f)) == HEADLINE_MAX_CHARS and display_headline(f).endswith("…")
    a = display_alert(f)
    assert len(a.text) == ALERT_MAX_CHARS and a.status == "bad"


def test_chips_use_tighter_limits_and_first_three_rows() -> None:
    rows = [{"label": "Outreaches", "value": "1234567"}, {"label": "A", "value": "1"},
            {"label": "B", "value": "2"}, {"label": "C", "value": "3"}]
    chips = display_chips(feed(rows=rows))
    assert [c.label for c in chips] == ["Outreac…", "A", "B"]
    assert chips[0].value == "12345…"


def test_build_alert_option_parses_trailing_status() -> None:
    def args(alert):
        return argparse.Namespace(title="T", status=None, kind="dashboard", row=None, progress=None,
                                  message=None, headline="Do the thing", alert=alert,
                                  stale_after=None, tap_url=None)
    d = build_feed(args("PR #1 stale in 2d:warn"), NOW)
    assert d["alert"] == {"text": "PR #1 stale in 2d", "status": "warn"}
    assert d["headline"] == "Do the thing"
    # a colon inside the text without a status word stays part of the text
    assert build_feed(args("At 9:30 call"), NOW)["alert"] == {"text": "At 9:30 call"}


def test_preview_draws_bezel_headline_alert_and_chips() -> None:
    f = feed(status="warn", headline="Review 2 pull requests",
             alert={"text": "Deploy failed", "status": "bad"},
             rows=[{"label": "Inbox", "value": "3"},
                   {"label": "Streak", "value": "2 wk", "status": "ok"}],
             progress={"label": "Tasks", "value": 4, "max": 5})
    svg = render(parse(f.to_dict() | {"updated": "2026-11-16T06:40:00Z"}), NOW)
    assert "TODAY · THU" in svg and ">Review 2 pull" in svg  # the headline wraps to 2 lines
    assert "● Deploy failed" in svg and "INBOX" in svg and "STREAK" in svg
    assert svg.count("<circle") >= 2 and "<path" in svg  # face + bezel track, bezel arc


def test_dashboard_without_progress_has_no_bezel() -> None:
    svg = render(parse(feed(headline="Quiet day").to_dict()), NOW)
    assert svg.count("<circle") == 1 and "<path" not in svg
