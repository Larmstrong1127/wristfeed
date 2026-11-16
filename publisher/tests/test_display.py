"""Clock-skew guard (rule 4), fresh/stale states, footer and truncation."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from wristfeed.feed import (
    Feed,
    Progress,
    Row,
    age,
    age_label,
    display_message,
    display_rows,
    display_state,
    display_title,
    effective_updated,
    footer,
    progress_text,
    truncate,
)

T0 = datetime(2026, 11, 16, 6, 40, tzinfo=timezone.utc)


def feed(**kw) -> Feed:
    return Feed(version=1, title=kw.pop("title", "JARVIS"), updated=kw.pop("updated", T0), **kw)


# ------------------------------------------------------------------ rule 4


@pytest.mark.parametrize(
    ("ahead", "trusted"),
    [
        (timedelta(0), True),
        (timedelta(minutes=2), True),
        (timedelta(minutes=2, seconds=1), False),
        (timedelta(hours=5), False),
        (-timedelta(hours=5), True),
    ],
)
def test_clock_skew_guard(ahead: timedelta, trusted: bool) -> None:
    fetched = T0
    updated = T0 + ahead
    assert effective_updated(updated, fetched) == (updated if trusted else fetched)


def test_future_updated_ages_from_fetch_time() -> None:
    f = feed(updated=T0 + timedelta(hours=3))
    assert age(f, fetched_at=T0, now=T0 + timedelta(minutes=10)) == timedelta(minutes=10)


def test_age_never_negative() -> None:
    f = feed(updated=T0 + timedelta(minutes=1))
    assert age(f, fetched_at=T0, now=T0) == timedelta(0)


# ------------------------------------------------------------ fresh / stale


@pytest.mark.parametrize(
    ("minutes", "state"), [(0, "fresh"), (359, "fresh"), (360, "stale"), (1000, "stale")]
)
def test_fresh_until_stale_after(minutes: int, state: str) -> None:
    now = T0 + timedelta(minutes=minutes)
    assert display_state(feed(), fetched_at=T0, now=now) == state


def test_custom_stale_after() -> None:
    f = feed(stale_after_minutes=1)
    assert display_state(f, T0, T0 + timedelta(seconds=59)) == "fresh"
    assert display_state(f, T0, T0 + timedelta(seconds=60)) == "stale"


@pytest.mark.parametrize(
    ("delta", "label"),
    [
        (timedelta(0), "0m ago"),
        (timedelta(seconds=59), "0m ago"),
        (timedelta(minutes=59, seconds=59), "59m ago"),
        (timedelta(minutes=60), "1h ago"),
        (timedelta(hours=5, minutes=59), "5h ago"),
        (timedelta(days=2), "48h ago"),
    ],
)
def test_age_label(delta: timedelta, label: str) -> None:
    assert age_label(delta) == label


def test_footer_fresh_and_stale() -> None:
    assert footer(feed(), T0, T0 + timedelta(minutes=12)) == "12m ago"
    assert footer(feed(), T0, T0 + timedelta(hours=7)) == "Old data · 7h ago"


# --------------------------------------------------------------- truncation


@pytest.mark.parametrize(
    ("text", "limit", "shown"),
    [
        ("abc", 3, "abc"),
        ("abcd", 3, "ab…"),
        ("", 5, ""),
        ("x" * 24, 24, "x" * 24),
        ("x" * 25, 24, "x" * 23 + "…"),
    ],
)
def test_truncate(text: str, limit: int, shown: str) -> None:
    assert truncate(text, limit) == shown
    assert len(truncate(text, limit)) <= limit


def test_display_limits_match_spec() -> None:
    f = feed(
        title="T" * 30,
        rows=(Row("L" * 13, "V" * 11, "ok"), Row("short", "1")),
        message="M" * 61,
    )
    assert display_title(f) == "T" * 23 + "…"
    assert display_rows(f)[0] == Row("L" * 11 + "…", "V" * 9 + "…", "ok")
    assert display_rows(f)[1] == Row("short", "1")
    assert display_message(f) == "M" * 59 + "…"
    assert display_message(feed(message="M" * 60)) == "M" * 60
    assert display_message(feed()) is None


def test_progress_text() -> None:
    assert progress_text(Progress("A", 1, 5)) == "1/5"
    assert progress_text(Progress("A", 2.5, 10.0)) == "2.5/10"
