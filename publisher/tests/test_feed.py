"""One or more tests per SPEC.md field-table row and numbered rule."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

import pytest

from wristfeed.feed import (
    DEFAULT_STALE_AFTER_MINUTES,
    MAX_BYTES,
    Progress,
    Row,
    parse,
    parse_bytes,
    parse_rfc3339,
)

U = "2026-11-16T06:40:00Z"


def doc(**fields: Any) -> dict[str, Any]:
    base: dict[str, Any] = {"wristfeed": 1, "title": "JARVIS", "updated": U}
    base.update(fields)
    return {k: v for k, v in base.items() if v is not _DROP}


_DROP = object()


def ok(d: Any):
    r = parse(d)
    assert r.outcome == "ok", r.problems
    assert r.feed is not None
    return r


# ---------------------------------------------------------------- wristfeed


def test_version_1_is_supported() -> None:
    assert ok(doc()).feed.version == 1


@pytest.mark.parametrize("v", [0, 2, 99, -1])
def test_other_integer_versions_are_unsupported(v: int) -> None:
    r = parse(doc(wristfeed=v))
    assert r.outcome == "unsupported" and r.feed is None


@pytest.mark.parametrize("v", ["1", 1.0, True, None, [1]])
def test_non_integer_version_is_malformed(v: Any) -> None:
    assert parse(doc(wristfeed=v)).outcome == "malformed"


def test_missing_version_is_malformed() -> None:
    assert parse(doc(wristfeed=_DROP)).outcome == "malformed"


def test_unsupported_wins_over_other_broken_fields() -> None:
    assert parse({"wristfeed": 2}).outcome == "unsupported"


# --------------------------------------------------------------------- title


def test_title_kept_untruncated_in_model() -> None:
    assert ok(doc(title="x" * 50)).feed.title == "x" * 50


@pytest.mark.parametrize("t", [_DROP, None, 5, "", "   ", ["a"]])
def test_broken_title_is_malformed(t: Any) -> None:
    assert parse(doc(title=t)).outcome == "malformed"


# ------------------------------------------------------------------- updated


@pytest.mark.parametrize(
    ("text", "iso"),
    [
        ("2026-11-16T06:40:00Z", "2026-11-16T06:40:00+00:00"),
        ("2026-11-16t06:40:00z", "2026-11-16T06:40:00+00:00"),
        ("2026-11-15T22:40:00-08:00", "2026-11-16T06:40:00+00:00"),
        ("2026-11-16T12:10:00+05:30", "2026-11-16T06:40:00+00:00"),
        ("2026-11-16T06:40:00.123456789Z", "2026-11-16T06:40:00.123456+00:00"),
    ],
)
def test_rfc3339_accepted(text: str, iso: str) -> None:
    assert parse_rfc3339(text) == datetime.fromisoformat(iso)


@pytest.mark.parametrize(
    "text",
    [
        "2026-11-16T06:40:00",
        "2026-11-16",
        "2026-11-16 06:40:00Z",
        "2026-13-16T06:40:00Z",
        "2026-02-30T06:40:00Z",
        "2026-11-16T06:40Z",
        "2026-11-16T06:40:00Z\n",
        "yesterday",
    ],
)
def test_rfc3339_rejected(text: str) -> None:
    assert parse_rfc3339(text) is None
    assert parse(doc(updated=text)).outcome == "malformed"


@pytest.mark.parametrize("u", [_DROP, None, 1763275200])
def test_broken_updated_is_malformed(u: Any) -> None:
    assert parse(doc(updated=u)).outcome == "malformed"


def test_updated_normalized_to_utc() -> None:
    feed = ok(doc(updated="2026-11-15T22:40:00-08:00")).feed
    assert feed.updated == datetime(2026, 11, 16, 6, 40, tzinfo=timezone.utc)
    assert feed.to_dict()["updated"] == U


# -------------------------------------------------------------- status, kind


def test_status_and_kind_defaults() -> None:
    r = ok(doc())
    assert (r.feed.status, r.feed.kind) == ("idle", "stats")
    assert r.problems == []


@pytest.mark.parametrize("s", ["ok", "warn", "bad", "idle"])
def test_known_statuses(s: str) -> None:
    assert ok(doc(status=s)).feed.status == s


@pytest.mark.parametrize("s", ["OK", "great", 1, None, ""])
def test_unknown_status_falls_back(s: Any) -> None:
    r = ok(doc(status=s))
    assert r.feed.status == "idle"
    assert [p.path for p in r.warnings] == ["status"]


@pytest.mark.parametrize("k", ["stats", "progress", "message"])
def test_known_kinds(k: str) -> None:
    assert ok(doc(kind=k)).feed.kind == k


@pytest.mark.parametrize("k", ["pie", None, 3])
def test_unknown_kind_falls_back(k: Any) -> None:
    assert ok(doc(kind=k)).feed.kind == "stats"


# ---------------------------------------------------------------------- rows


def test_rows_parsed_with_optional_status() -> None:
    rows = [{"label": "Apps", "value": "1/5", "status": "bad"}, {"label": "Asks", "value": "2"}]
    assert ok(doc(rows=rows)).feed.rows == (Row("Apps", "1/5", "bad"), Row("Asks", "2", None))


def test_rows_default_to_empty_even_for_stats() -> None:
    r = ok(doc(kind="stats"))
    assert r.feed.rows == () and r.problems == []


def test_more_than_three_rows_are_kept_but_only_three_shown() -> None:
    from wristfeed.feed import display_rows

    feed = ok(doc(rows=[{"label": str(i), "value": str(i)} for i in range(5)])).feed
    assert len(feed.rows) == 5
    assert [r.label for r in display_rows(feed)] == ["0", "1", "2"]


@pytest.mark.parametrize(
    "bad", ["x", 1, None, {"label": "a"}, {"value": "a"}, {"label": "a", "value": 3}]
)
def test_bad_row_dropped_others_kept(bad: Any) -> None:
    r = ok(doc(rows=[bad, {"label": "B", "value": "2"}]))
    assert r.feed.rows == (Row("B", "2"),)
    assert r.warnings


def test_bad_row_status_uncolors_but_keeps_row() -> None:
    r = ok(doc(rows=[{"label": "B", "value": "2", "status": "purple"}]))
    assert r.feed.rows == (Row("B", "2", None),)
    assert [p.path for p in r.warnings] == ["rows[0].status"]


def test_rows_not_array_falls_back() -> None:
    r = ok(doc(rows={"label": "a", "value": "b"}))
    assert r.feed.rows == () and r.warnings


# ------------------------------------------------------------------ progress


def test_progress_parsed() -> None:
    p = ok(doc(progress={"label": "Apps", "value": 1, "max": 5})).feed.progress
    assert p == Progress("Apps", 1, 5)


@pytest.mark.parametrize(("value", "clamped"), [(-2, 0), (7, 5), (2.5, 2.5)])
def test_progress_value_clamped_without_warning(value: float, clamped: float) -> None:
    r = ok(doc(progress={"label": "A", "value": value, "max": 5}))
    assert r.feed.progress.value == clamped
    assert r.problems == []


@pytest.mark.parametrize(
    "bad",
    [
        {"label": "A", "value": "1", "max": 5},
        {"label": "A", "value": 1, "max": 0},
        {"label": "A", "value": 1, "max": -5},
        {"label": "A", "value": True, "max": 5},
        {"label": "A", "value": 1},
        {"value": 1, "max": 5},
        {"label": 3, "value": 1, "max": 5},
        [1, 5],
        "1/5",
    ],
)
def test_bad_progress_falls_back_to_none(bad: Any) -> None:
    r = ok(doc(progress=bad))
    assert r.feed.progress is None and r.warnings


def test_null_progress_is_absent_without_warning() -> None:
    r = ok(doc(progress=None))
    assert r.feed.progress is None and r.problems == []


# ------------------------------------------------- message, stale, tap_url


def test_message() -> None:
    assert ok(doc(message="All quiet")).feed.message == "All quiet"
    assert ok(doc(message=None)).problems == []


def test_bad_message_falls_back() -> None:
    r = ok(doc(message=42))
    assert r.feed.message is None and r.warnings


def test_stale_after_default_and_value() -> None:
    assert ok(doc()).feed.stale_after_minutes == DEFAULT_STALE_AFTER_MINUTES == 360
    assert ok(doc(stale_after_minutes=1)).feed.stale_after_minutes == 1


@pytest.mark.parametrize("v", [0, -5, 1.5, 60.0, "60", True, None])
def test_bad_stale_after_falls_back(v: Any) -> None:
    r = ok(doc(stale_after_minutes=v))
    assert r.feed.stale_after_minutes == 360 and r.warnings


def test_tap_url() -> None:
    assert ok(doc(tap_url="https://example.com/x")).feed.tap_url == "https://example.com/x"
    assert ok(doc(tap_url=None)).problems == []


@pytest.mark.parametrize("u", ["http://example.com", "example.com", "https://", 5, "javascript:x"])
def test_bad_tap_url_falls_back(u: Any) -> None:
    r = ok(doc(tap_url=u))
    assert r.feed.tap_url is None and r.warnings


# ------------------------------------------------------------- rules 1, 2, 3


def test_rule1_unknown_fields_ignored() -> None:
    r = ok(doc(extra={"a": 1}, rows=[{"label": "a", "value": "b", "colour": "red"}]))
    assert r.problems == []


def test_rule2_missing_optionals_use_defaults() -> None:
    d = ok(doc()).feed.to_dict()
    assert d == {
        "wristfeed": 1,
        "title": "JARVIS",
        "updated": U,
        "status": "idle",
        "kind": "stats",
        "rows": [],
        "progress": None,
        "message": None,
        "stale_after_minutes": 360,
        "tap_url": None,
    }


def test_rule3_fallback_is_per_field() -> None:
    r = ok(doc(status="nope", message="still here", stale_after_minutes=0))
    assert r.feed.status == "idle"
    assert r.feed.message == "still here"
    assert {p.path for p in r.warnings} == {"status", "stale_after_minutes"}


def test_rule3_broken_required_field_reports_all_problems() -> None:
    r = parse(doc(title=None, updated="x", status="nope"))
    assert r.outcome == "malformed"
    assert {p.path for p in r.problems} == {"title", "updated", "status"}


@pytest.mark.parametrize("body", [[], "feed", 1, None])
def test_non_object_is_malformed(body: Any) -> None:
    assert parse(body).outcome == "malformed"


# -------------------------------------------------------------------- rule 5


def _padded(size: int) -> bytes:
    head = json.dumps(doc(pad=""))[:-2]  # ends with '"pad": "' minus closing
    filler = size - len(head.encode()) - 2
    body = (head + "x" * filler + '"}').encode()
    assert len(body) == size
    return body


def test_rule5_exactly_64kb_is_accepted() -> None:
    assert parse_bytes(_padded(MAX_BYTES)).outcome == "ok"


def test_rule5_over_64kb_is_malformed() -> None:
    assert parse_bytes(_padded(MAX_BYTES + 1)).outcome == "malformed"


@pytest.mark.parametrize("body", [b"", b"{", b"\xff\xfe", b"not json"])
def test_invalid_json_is_malformed(body: bytes) -> None:
    assert parse_bytes(body).outcome == "malformed"


def test_utf8_bom_is_tolerated() -> None:
    assert parse_bytes(b"\xef\xbb\xbf" + json.dumps(doc()).encode()).outcome == "ok"
