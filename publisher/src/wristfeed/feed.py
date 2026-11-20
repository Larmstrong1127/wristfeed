"""Reference implementation of the WristFeed v1 parsing rules (see SPEC.md).

The Kotlin watch app mirrors this module rule for rule, so every rule is a
small, named function or constant. Resolved ambiguities are marked "DECISION".

Outcomes of :func:`parse`:

* ``"ok"``          - a usable v1 feed (possibly with fallback warnings).
* ``"malformed"``   - a required field is missing/broken, the body is not a
                      JSON object, or it exceeds the size limit.
* ``"unsupported"`` - ``wristfeed`` is an integer other than 1.

Display states (``fresh``/``stale``) are computed separately by
:func:`display_state` because they depend on the clock, not the document.
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal, localcontext
from typing import Any, Literal

SUPPORTED_VERSION = 1
MAX_BYTES = 64 * 1024  # Rule 5. DECISION: 65536 bytes allowed, 65537 is malformed.
CLOCK_SKEW_TOLERANCE = timedelta(minutes=2)  # Rule 4.

STATUSES = ("ok", "warn", "bad", "idle")
KINDS = ("stats", "progress", "message", "dashboard")
DEFAULT_STATUS = "idle"
DEFAULT_KIND = "stats"
DEFAULT_STALE_AFTER_MINUTES = 360
MAX_STALE_AFTER_MINUTES = 525_600  # one year
MAX_VERSION_MAGNITUDE = 2**53  # wristfeed must be an integer in -2^53..2^53
MAX_NUMBER_MAGNITUDE = 1e308  # larger magnitudes are "not a number" (SPEC rule 7)
MAX_DEPTH = 32  # SPEC rule 6: the root container is level 1
INT64_MIN, INT64_MAX = -(2**63), 2**63 - 1
MAX_OFFSET_MINUTES = 18 * 60

MAX_ROWS_SHOWN = 3
TITLE_MAX_CHARS = 24
ROW_LABEL_MAX_CHARS = 12
ROW_VALUE_MAX_CHARS = 10
MESSAGE_MAX_CHARS = 60
HEADLINE_MAX_CHARS = 44  # dashboard
ALERT_MAX_CHARS = 32  # dashboard
CHIP_LABEL_MAX_CHARS = 8  # dashboard chips
CHIP_VALUE_MAX_CHARS = 6
DEFAULT_ALERT_STATUS = "warn"
ELLIPSIS = "…"

Outcome = Literal["ok", "malformed", "unsupported"]
DisplayState = Literal["fresh", "stale"]
Severity = Literal["error", "warning"]

_RFC3339 = re.compile(
    r"^(?P<base>\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2})"
    r"(?:\.(?P<frac>\d{1,9}))?(?P<zone>[Zz]|[+-](?P<oh>\d{2}):(?P<om>\d{2}))$"
)


@dataclass(frozen=True)
class Problem:
    """One finding. ``error`` makes the feed unusable; ``warning`` means a fallback."""

    path: str
    message: str
    severity: Severity

    def __str__(self) -> str:
        return f"{self.severity}: {self.path}: {self.message}"


@dataclass(frozen=True)
class Row:
    label: str
    value: str
    status: str | None = None  # None = uncolored value


@dataclass(frozen=True)
class Progress:
    label: str
    value: float  # already clamped to 0..max
    max: float


@dataclass(frozen=True)
class Alert:
    text: str
    status: str = DEFAULT_ALERT_STATUS


@dataclass(frozen=True)
class Feed:
    version: int
    title: str
    updated: datetime  # timezone-aware, normalized to UTC
    status: str = DEFAULT_STATUS
    kind: str = DEFAULT_KIND
    rows: tuple[Row, ...] = ()
    progress: Progress | None = None
    message: str | None = None
    stale_after_minutes: int = DEFAULT_STALE_AFTER_MINUTES
    tap_url: str | None = None
    headline: str | None = None
    alert: Alert | None = None

    def to_dict(self) -> dict[str, Any]:
        """Normalized view used by the conformance corpus (``updated`` as ``...Z``)."""
        return {
            "wristfeed": self.version,
            "title": self.title,
            "updated": format_utc(self.updated),
            "status": self.status,
            "kind": self.kind,
            "rows": [{"label": r.label, "value": r.value, "status": r.status} for r in self.rows],
            "progress": None
            if self.progress is None
            else {
                "label": self.progress.label,
                "value": self.progress.value,
                "max": self.progress.max,
            },
            "message": self.message,
            "stale_after_minutes": self.stale_after_minutes,
            "tap_url": self.tap_url,
            "headline": self.headline,
            "alert": None
            if self.alert is None
            else {"text": self.alert.text, "status": self.alert.status},
        }


@dataclass
class ParseResult:
    outcome: Outcome
    feed: Feed | None = None
    problems: list[Problem] = field(default_factory=list)

    @property
    def errors(self) -> list[Problem]:
        return [p for p in self.problems if p.severity == "error"]

    @property
    def warnings(self) -> list[Problem]:
        return [p for p in self.problems if p.severity == "warning"]

    @property
    def is_strictly_valid(self) -> bool:
        """True when the document is a clean v1 feed: no errors and no fallbacks."""
        return self.outcome == "ok" and not self.problems


# --------------------------------------------------------------------------- helpers


def _is_int(v: Any) -> bool:
    # JSON booleans arrive as Python bool, which subclasses int: reject them.
    return isinstance(v, int) and not isinstance(v, bool)


def _is_number(v: Any) -> bool:
    """SPEC rule 7: finite and magnitude <= 1e308. Ints are compared exactly (no float())."""
    if _is_int(v):
        return -MAX_NUMBER_MAGNITUDE <= v <= MAX_NUMBER_MAGNITUDE
    return isinstance(v, float) and math.isfinite(v) and abs(v) <= MAX_NUMBER_MAGNITUDE


def format_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_rfc3339(text: str) -> datetime | None:
    """Parse an RFC 3339 timestamp that carries ``Z`` or an explicit offset.

    DECISION: a timestamp without a zone, or a date only, is rejected (the SPEC
    requires UTC ``Z`` or an offset). Fractional seconds are accepted.
    """
    match = _RFC3339.fullmatch(text)
    if match is None:
        return None
    if match["oh"] is not None:
        oh, om = int(match["oh"]), int(match["om"])
        if om >= 60 or oh * 60 + om > MAX_OFFSET_MINUTES:
            return None  # DECISION: same limits as java.time (|offset| <= 18:00)
    # Normalize so datetime.fromisoformat accepts it on Python 3.10.
    fraction = (match["frac"] or "")[:6].ljust(6, "0") if match["frac"] else ""
    zone = "+00:00" if match["zone"] in ("Z", "z") else match["zone"]
    candidate = f"{match['base']}{'.' + fraction if fraction else ''}{zone}"
    try:
        dt = datetime.fromisoformat(candidate.replace("t", "T"))
    except ValueError:
        return None
    return dt.astimezone(timezone.utc)


# --------------------------------------------------------------- required fields


def _check_version(doc: dict[str, Any]) -> tuple[Outcome | None, Problem | None]:
    if "wristfeed" not in doc:
        return "malformed", Problem("wristfeed", "required field missing", "error")
    v = doc["wristfeed"]
    if not _is_int(v):
        # DECISION: a non-integer version (e.g. "1", 1.0) is malformed, not unsupported.
        return "malformed", Problem("wristfeed", "must be an integer", "error")
    if not -MAX_VERSION_MAGNITUDE <= v <= MAX_VERSION_MAGNITUDE:
        return "malformed", Problem("wristfeed", "must be an integer in -2^53..2^53", "error")
    if v != SUPPORTED_VERSION:
        return "unsupported", Problem("wristfeed", f"unsupported feed version {v}", "error")
    return None, None


def _parse_title(doc: dict[str, Any], problems: list[Problem]) -> str | None:
    t = doc.get("title")
    if "title" not in doc:
        problems.append(Problem("title", "required field missing", "error"))
    elif not isinstance(t, str) or not t.strip():
        # DECISION: an empty or whitespace-only title is malformed.
        problems.append(Problem("title", "must be a non-empty string", "error"))
    else:
        return t
    return None


def _parse_updated(doc: dict[str, Any], problems: list[Problem]) -> datetime | None:
    if "updated" not in doc:
        problems.append(Problem("updated", "required field missing", "error"))
        return None
    u = doc["updated"]
    dt = parse_rfc3339(u) if isinstance(u, str) else None
    if dt is None:
        problems.append(
            Problem("updated", "must be an RFC 3339 timestamp with Z or offset", "error")
        )
    return dt


# --------------------------------------------------------------- optional fields


def _fallback(problems: list[Problem], path: str, message: str) -> None:
    problems.append(Problem(path, f"{message}; using default", "warning"))


def _parse_enum(
    doc: dict[str, Any], key: str, allowed: tuple[str, ...], default: str, problems: list[Problem]
) -> str:
    if key not in doc:
        return default
    v = doc[key]
    if isinstance(v, str) and v in allowed:
        return v
    _fallback(problems, key, f"must be one of {', '.join(allowed)}")
    return default


def _parse_row(raw: Any, path: str, problems: list[Problem]) -> Row | None:
    """A row needs string ``label`` and ``value``; a bad row is dropped.

    DECISION: a numeric ``value`` is NOT coerced to a string; it drops the row.
    A bad row ``status`` falls back to None (uncolored) and keeps the row.
    """
    if not isinstance(raw, dict):
        _fallback(problems, path, "row must be an object; row dropped")
        return None
    label, value = raw.get("label"), raw.get("value")
    if not isinstance(label, str) or not isinstance(value, str):
        _fallback(problems, path, "row needs string label and value; row dropped")
        return None
    status = raw.get("status")
    if status is not None and status not in STATUSES:
        _fallback(problems, f"{path}.status", f"must be one of {', '.join(STATUSES)}")
        status = None
    return Row(label, value, status)


def _parse_rows(doc: dict[str, Any], problems: list[Problem]) -> tuple[Row, ...]:
    """All valid rows are kept; only the first :data:`MAX_ROWS_SHOWN` are displayed."""
    if "rows" not in doc:
        return ()
    raw = doc["rows"]
    if not isinstance(raw, list):
        _fallback(problems, "rows", "must be an array")
        return ()
    rows = (_parse_row(r, f"rows[{i}]", problems) for i, r in enumerate(raw))
    return tuple(r for r in rows if r is not None)


def _parse_progress(doc: dict[str, Any], problems: list[Problem]) -> Progress | None:
    raw = doc.get("progress")
    if raw is None:
        return None
    if not isinstance(raw, dict):
        _fallback(problems, "progress", "must be an object")
        return None
    label, value, mx = raw.get("label"), raw.get("value"), raw.get("max")
    if not isinstance(label, str):
        _fallback(problems, "progress.label", "must be a string")
        return None
    if not _is_number(value) or not _is_number(mx):
        _fallback(problems, "progress", "value and max must be numbers")
        return None
    if mx <= 0:
        _fallback(problems, "progress.max", "must be > 0")
        return None
    return Progress(label, clamp(value, 0, mx), mx)


def clamp(v: float, lo: float, hi: float) -> float:
    """Clamp silently: an out-of-range progress value is legal (SPEC: clamped)."""
    return max(lo, min(hi, v))


def _parse_message(doc: dict[str, Any], problems: list[Problem]) -> str | None:
    m = doc.get("message")
    if m is None or isinstance(m, str):
        return m
    _fallback(problems, "message", "must be a string or null")
    return None


def _parse_headline(doc: dict[str, Any], problems: list[Problem]) -> str | None:
    h = doc.get("headline")
    if h is None or isinstance(h, str):
        return h
    _fallback(problems, "headline", "must be a string or null")
    return None


def _parse_alert(doc: dict[str, Any], problems: list[Problem]) -> Alert | None:
    """DECISION: an alert needs a non-blank string ``text``; a missing ``status`` is ``warn``,
    a wrong one falls back to ``warn`` (the alert itself is kept)."""
    a = doc.get("alert")
    if a is None:
        return None
    text = a.get("text") if isinstance(a, dict) else None
    if not isinstance(text, str) or not text.strip():
        _fallback(problems, "alert", "must be null or an object with a non-empty string text")
        return None
    status = a.get("status", DEFAULT_ALERT_STATUS)
    if status not in STATUSES:
        _fallback(problems, "alert.status", f"must be one of {', '.join(STATUSES)}")
        status = DEFAULT_ALERT_STATUS
    return Alert(text, status)


def _parse_stale_after(doc: dict[str, Any], problems: list[Problem]) -> int:
    if "stale_after_minutes" not in doc:
        return DEFAULT_STALE_AFTER_MINUTES
    v = doc["stale_after_minutes"]
    if _is_int(v) and 1 <= v <= MAX_STALE_AFTER_MINUTES:
        return v
    _fallback(problems, "stale_after_minutes", f"must be an integer 1..{MAX_STALE_AFTER_MINUTES}")
    return DEFAULT_STALE_AFTER_MINUTES


def _parse_tap_url(doc: dict[str, Any], problems: list[Problem]) -> str | None:
    """DECISION: only ``https://`` URLs (case-insensitive scheme) are kept."""
    u = doc.get("tap_url")
    if u is None:
        return None
    if isinstance(u, str) and u[:8].lower() == "https://" and len(u) > 8:
        return u
    _fallback(problems, "tap_url", "must be an https URL or null")
    return None


# -------------------------------------------------------------------------- parse


def parse(doc: Any) -> ParseResult:
    """Apply SPEC rules 1-3 to an already-decoded JSON value."""
    if not isinstance(doc, dict):
        return ParseResult("malformed", None, [Problem("$", "must be a JSON object", "error")])

    outcome, problem = _check_version(doc)
    if outcome is not None:
        return ParseResult(outcome, None, [problem] if problem else [])

    problems: list[Problem] = []
    title = _parse_title(doc, problems)
    updated = _parse_updated(doc, problems)
    status = _parse_enum(doc, "status", STATUSES, DEFAULT_STATUS, problems)
    kind = _parse_enum(doc, "kind", KINDS, DEFAULT_KIND, problems)
    rows = _parse_rows(doc, problems)
    progress = _parse_progress(doc, problems)
    message = _parse_message(doc, problems)
    stale_after = _parse_stale_after(doc, problems)
    tap_url = _parse_tap_url(doc, problems)
    headline = _parse_headline(doc, problems)
    alert = _parse_alert(doc, problems)

    if title is None or updated is None:
        return ParseResult("malformed", None, problems)
    feed = Feed(
        version=SUPPORTED_VERSION,
        title=title,
        updated=updated,
        status=status,
        kind=kind,
        rows=rows,
        progress=progress,
        message=message,
        stale_after_minutes=stale_after,
        tap_url=tap_url,
        headline=headline,
        alert=alert,
    )
    return ParseResult("ok", feed, problems)


def parse_bytes(body: bytes) -> ParseResult:
    """Apply rule 5 (size), decode UTF-8 JSON, then :func:`parse`."""
    if len(body) > MAX_BYTES:
        return ParseResult(
            "malformed", None, [Problem("$", f"larger than {MAX_BYTES} bytes", "error")]
        )
    try:
        text = body.decode("utf-8-sig")
        if _nesting_depth(text) > MAX_DEPTH:
            return ParseResult(
                "malformed", None, [Problem("$", f"nested deeper than {MAX_DEPTH}", "error")]
            )
        doc = json.loads(text, parse_int=_parse_int, parse_constant=_reject_constant)
    except (UnicodeDecodeError, ValueError, OverflowError, RecursionError) as exc:
        # json.JSONDecodeError is a ValueError; so are NaN/Infinity (rule 6).
        return ParseResult("malformed", None, [Problem("$", f"not valid JSON: {exc}", "error")])
    return parse(doc)


def _parse_int(text: str) -> int | float:
    """SPEC rule 7 (matches the watch's MiniJson): an integer literal outside int64 is
    read as a float, which is inf beyond ~1e308. Avoids int()'s 4300-digit ValueError."""
    if len(text) <= 20:
        n = int(text)
        if INT64_MIN <= n <= INT64_MAX:
            return n
    return float(text)


def _reject_constant(name: str) -> Any:
    raise ValueError(f"{name} is not JSON")


def _nesting_depth(text: str) -> int:
    """Deepest ``[``/``{`` nesting outside strings (the root container is 1).

    Only for the rule-6 depth limit; the scan stops early once over the limit."""
    depth = deepest = 0
    in_string = escaped = False
    for ch in text:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch in "[{":
            depth += 1
            if depth > deepest:
                deepest = depth
                if deepest > MAX_DEPTH:
                    return deepest
        elif ch in "]}":
            depth -= 1
    return deepest


# ---------------------------------------------------------------- clock and age


def effective_updated(updated: datetime, fetched_at: datetime) -> datetime:
    """Rule 4: an ``updated`` more than 2 minutes after the fetch is replaced by the fetch time.

    Exactly 2 minutes ahead is still trusted (the rule says "more than").
    """
    return fetched_at if updated - fetched_at > CLOCK_SKEW_TOLERANCE else updated


def age(feed: Feed, fetched_at: datetime, now: datetime) -> timedelta:
    """Age since the (skew-guarded) ``updated``; never negative."""
    return max(timedelta(0), now - effective_updated(feed.updated, fetched_at))


def display_state(feed: Feed, fetched_at: datetime, now: datetime) -> DisplayState:
    """``fresh`` while ``age < stale_after_minutes``, then ``stale``."""
    limit = timedelta(minutes=feed.stale_after_minutes)
    return "fresh" if age(feed, fetched_at, now) < limit else "stale"


def age_label(a: timedelta) -> str:
    """Footer age. DECISION: under 60 minutes -> "Nm ago" (floor), else "Nh ago" (floor)."""
    minutes = int(a.total_seconds() // 60)
    return f"{minutes}m ago" if minutes < 60 else f"{minutes // 60}h ago"


def footer(feed: Feed, fetched_at: datetime, now: datetime) -> str:
    a = age(feed, fetched_at, now)
    label = age_label(a)
    return label if display_state(feed, fetched_at, now) == "fresh" else f"Old data · {label}"


# --------------------------------------------------------------------- display


def truncate(text: str, limit: int) -> str:
    """Cut to at most ``limit`` characters, the last being "…" when cut.

    DECISION: "characters" are Unicode code points (Kotlin: codePointCount), so the
    shown text including the ellipsis is never longer than ``limit``.
    """
    if len(text) <= limit:
        return text
    return text[: limit - 1] + ELLIPSIS


def display_title(feed: Feed) -> str:
    return truncate(feed.title, TITLE_MAX_CHARS)


def display_rows(feed: Feed) -> list[Row]:
    return [
        Row(
            truncate(r.label, ROW_LABEL_MAX_CHARS), truncate(r.value, ROW_VALUE_MAX_CHARS), r.status
        )
        for r in feed.rows[:MAX_ROWS_SHOWN]
    ]


def display_message(feed: Feed) -> str | None:
    return None if feed.message is None else truncate(feed.message, MESSAGE_MAX_CHARS)


def display_headline(feed: Feed) -> str | None:
    return None if feed.headline is None else truncate(feed.headline, HEADLINE_MAX_CHARS)


def display_alert(feed: Feed) -> Alert | None:
    if feed.alert is None:
        return None
    return Alert(truncate(feed.alert.text, ALERT_MAX_CHARS), feed.alert.status)


def display_chips(feed: Feed) -> list[Row]:
    """Dashboard chips: the first 3 rows with the tighter chip limits."""
    return [
        Row(
            truncate(r.label, CHIP_LABEL_MAX_CHARS),
            truncate(r.value, CHIP_VALUE_MAX_CHARS),
            r.status,
        )
        for r in feed.rows[:MAX_ROWS_SHOWN]
    ]


def format_number(v: float) -> str:
    """SPEC "Number display": whole and |v| < 1e15 -> plain integer ("1/5", not "1.0/5.0");
    otherwise 6 significant digits, round half up on the exact binary value, trailing zeros
    stripped, plain notation (no exponent). Kotlin: BigDecimal.round(MathContext(6))."""
    f = float(v)
    if f.is_integer() and abs(f) < 1e15:
        return str(int(f))
    with localcontext() as ctx:
        ctx.prec = 6
        ctx.rounding = ROUND_HALF_UP
        d = (+Decimal(f)).normalize()
    return format(d, "f")


def progress_text(p: Progress) -> str:
    return f"{format_number(p.value)}/{format_number(p.max)}"
