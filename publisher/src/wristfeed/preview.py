"""Render a feed as an SVG of the round Wear OS tile (``wristfeed preview``).

Mirrors ``android/app/.../tile/TileLayouts.kt``: the same element tree (title, rows /
progress ring / message, footer), the same sp/dp sizes, the same colors and the same
50% dimming when stale. All text rules come from :mod:`wristfeed.feed` (no copies).

Layout is approximated without a font engine: line height is 1.2 x font size and text
width is estimated at 0.55 x font size per character (used only for row spacing and
message wrapping). Output is deterministic: no timestamps, ids or randomness.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from datetime import datetime
from xml.sax.saxutils import escape

from wristfeed.feed import (
    Feed,
    ParseResult,
    age,
    age_label,
    display_alert,
    display_chips,
    display_headline,
    display_message,
    display_rows,
    display_state,
    display_title,
    footer,
    progress_text,
    truncate,
)

STATES = ("fresh", "stale", "unreachable", "malformed", "unsupported", "loading")

# XML 1.0 forbids most control characters and U+FFFE/U+FFFF; a feed may carry
# them (the parser accepts any string), so they become U+FFFD before escaping
# or the SVG is not well-formed (second review, 2026-11-15). Built from code
# points so the allowed ranges stay readable.
_XML_ALLOWED = [(0x09, 0x0A), (0x0D, 0x0D), (0x20, 0xD7FF), (0xE000, 0xFFFD), (0x10000, 0x10FFFF)]
_XML_INVALID = re.compile(
    "[^" + "".join(re.escape(chr(a)) + "-" + re.escape(chr(b)) for a, b in _XML_ALLOWED) + "]"
)
_REPLACEMENT = chr(0xFFFD)


def _xml_safe(text: str) -> str:
    return _XML_INVALID.sub(_REPLACEMENT, text)


# Colors: same ARGB values as TileLayouts.kt (alpha is always FF there).
WHITE = 0xFFFFFF
GREY_TEXT = 0xBDBDBD
TRACK = 0x303030
STATUS_COLORS = {"ok": 0x4CAF50, "warn": 0xFFB300, "bad": 0xE53935, "idle": 0x9E9E9E}

# Headlines: Display.stateHeadline (Kotlin).
HEADLINES = {
    "loading": "Loading…",
    "unreachable": "Can't reach feed",
    "malformed": "Bad feed",
    "unsupported": "Unsupported feed version",
}
MALFORMED_REASON_MAX = 40  # TileLayouts: Display.truncate(state.reason, 40)
ROW_LABEL_MAX = 12  # Display.MAX_LABEL, used for the progress label

DEFAULT_SIZE = 454
DP_PER_SCREEN = 227.0  # a 454 px round screen at density 2.0 (Galaxy Watch 6/7 large)
FONT = "Roboto, 'Helvetica Neue', Arial, sans-serif"
LINE = 1.2
CHAR_W = 0.55


def status_color(status: str | None) -> int:
    return STATUS_COLORS.get(status or "idle", STATUS_COLORS["idle"])


def dim(c: int, dimmed: bool) -> int:
    """TileLayouts.dim: scale each RGB channel to 50% (integer division)."""
    if not dimmed:
        return c
    return (((c >> 16) & 0xFF) // 2) << 16 | (((c >> 8) & 0xFF) // 2) << 8 | (c & 0xFF) // 2


def hex_color(c: int) -> str:
    return f"#{c:06X}"


# ---------------------------------------------------------------- element tree


@dataclass
class Text:
    s: str
    sp: float
    color: int
    bold: bool = False
    max_lines: int = 1


@dataclass
class Spacer:
    h: float


@dataclass
class StatRow:
    label: Text
    value: Text


@dataclass
class Ring:
    fraction: float
    center: Text
    label: Text | None
    accent: int


@dataclass
class Chips:
    """Dashboard chips: up to 3 (value, label) pairs side by side."""

    items: list[tuple[Text, Text]]


@dataclass
class Bezel:
    """Dashboard progress drawn around the screen edge."""

    fraction: float
    accent: int
    track: int


Element = Text | Spacer | StatRow | Ring | Chips
RING_DP = 72.0
# Dashboard sizes: TileLayouts.kt dashboard() uses the same numbers.
DASH_TRACK = 0x262626
DASH_MUTED = 0x9E9E9E
DASH_BEZEL_DP = 5.0
DASH_BEZEL_INSET_DP = 4.0
DASH_CHIP_DP = 56.0
DASH_HEADLINE_DP = 172.0
RING_THICK_DP = 6.0
MAX_TEXT_DP = 200.0  # wrap width estimate for multi-line text


def _wrap(t: Text) -> list[str]:
    per_line = max(1, int(MAX_TEXT_DP / (t.sp * CHAR_W)))
    words, lines, cur = t.s.split(" "), [], ""
    for w in words:
        cand = w if not cur else f"{cur} {w}"
        if len(cand) <= per_line or not cur:
            cur = cand
        else:
            lines.append(cur)
            cur = w
    lines.append(cur)
    if len(lines) > t.max_lines:  # maxLines clips; Android shows an ellipsis on the last line
        lines = lines[: t.max_lines]
        lines[-1] += "…"
    return lines


def _height(e: Element) -> float:
    if isinstance(e, Text):
        return len(_wrap(e)) * e.sp * LINE
    if isinstance(e, Spacer):
        return e.h
    if isinstance(e, StatRow):
        return max(e.label.sp, e.value.sp) * LINE
    if isinstance(e, Chips):
        return max((v.sp + lab.sp) * LINE for v, lab in e.items) if e.items else 0.0
    return RING_DP


def dashboard_column(feed: Feed, foot: str, dimmed: bool) -> tuple[list[Element], Bezel | None]:
    """Mirror of TileLayouts.dashboard(): eyebrow, headline, alert, chips, footer, bezel ring."""
    accent = dim(status_color(feed.status), dimmed)
    body = dim(WHITE, dimmed)
    muted = dim(DASH_MUTED, dimmed)
    col: list[Element] = [Text(display_title(feed).upper(), 11, muted, bold=True), Spacer(4)]
    headline = display_headline(feed)
    col.append(Text(headline or display_title(feed), 17, body, bold=True, max_lines=2))
    alert = display_alert(feed)
    if alert is not None:
        col += [Spacer(6), Text("● " + alert.text, 12, dim(status_color(alert.status), dimmed))]
    chips = display_chips(feed)
    if chips:
        items = [
            (
                Text(
                    r.value,
                    16,
                    dim(status_color(r.status), dimmed) if r.status else body,
                    bold=True,
                ),
                Text(r.label.upper(), 9, muted),
            )
            for r in chips
        ]
        col += [Spacer(10), Chips(items)]
    col += [Spacer(6), Text(foot, 10, muted)]
    p = feed.progress
    bezel = None
    if p is not None:
        bezel = Bezel(p.value / p.max if p.max else 0.0, accent, dim(DASH_TRACK, dimmed))
    return col, bezel


def message_column(headline: str, detail: str | None) -> list[Element]:
    col: list[Element] = [Text(headline, 16, WHITE, bold=True, max_lines=2)]
    if detail is not None:
        col += [Spacer(4), Text(detail, 12, GREY_TEXT, max_lines=2)]
    return col


def feed_column(feed: Feed, foot: str, dimmed: bool) -> list[Element]:
    accent = dim(status_color(feed.status), dimmed)
    body = dim(WHITE, dimmed)
    grey = dim(GREY_TEXT, dimmed)
    col: list[Element] = [Text(display_title(feed), 15, accent, bold=True), Spacer(6)]
    if feed.kind == "stats":
        rows = display_rows(feed)
        if not rows:
            col.append(Text("No rows", 13, grey))
        for r in rows:
            vcol = dim(status_color(r.status), dimmed) if r.status is not None else body
            col.append(StatRow(Text(r.label, 13, grey), Text(r.value, 14, vcol, bold=True)))
    elif feed.kind == "progress":
        p = feed.progress
        if p is None:
            col.append(Text("No progress", 13, grey))
        else:
            label = truncate(p.label, ROW_LABEL_MAX)
            col.append(
                Ring(
                    p.value / p.max if p.max else 0.0,
                    Text(progress_text(p), 14, body, bold=True),
                    Text(label, 10, grey) if label else None,
                    accent,
                )
            )
    else:
        col.append(Text(display_message(feed) or "", 13, body, max_lines=3))
    col += [Spacer(6), Text(foot, 11, grey)]
    return col


# ---------------------------------------------------------------- SVG output


def _fmt(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def _text_svg(t: Text, cx: float, top: float, k: float, anchor: str = "middle") -> list[str]:
    out = []
    lh = t.sp * LINE * k
    weight = ' font-weight="bold"' if t.bold else ""
    for i, line in enumerate(_wrap(t)):
        y = top + lh * i + lh / 2
        out.append(
            f'<text x="{_fmt(cx)}" y="{_fmt(y)}" font-size="{_fmt(t.sp * k)}"'
            f'{weight} fill="{hex_color(t.color)}" text-anchor="{anchor}"'
            f' dominant-baseline="central">{escape(_xml_safe(line))}</text>'
        )
    return out


def _arc(cx: float, cy: float, r: float, deg: float, color: int, sw: float) -> str:
    if deg >= 360:
        return (
            f'<circle cx="{_fmt(cx)}" cy="{_fmt(cy)}" r="{_fmt(r)}" fill="none"'
            f' stroke="{hex_color(color)}" stroke-width="{_fmt(sw)}"/>'
        )
    a = math.radians(deg)
    x1, y1 = cx, cy - r  # anchor 0 degrees = 12 o'clock, clockwise (ARC_ANCHOR_START)
    x2, y2 = cx + r * math.sin(a), cy - r * math.cos(a)
    large = 1 if deg > 180 else 0
    return (
        f'<path d="M {_fmt(x1)} {_fmt(y1)} A {_fmt(r)} {_fmt(r)} 0 {large} 1 '
        f'{_fmt(x2)} {_fmt(y2)}" fill="none" stroke="{hex_color(color)}"'
        f' stroke-width="{_fmt(sw)}" stroke-linecap="butt"/>'
    )


def render_elements(
    col: list[Element], size: int = DEFAULT_SIZE, bezel: Bezel | None = None
) -> str:
    k = size / DP_PER_SCREEN
    c = size / 2
    total = sum(_height(e) for e in col) * k
    y = c - total / 2
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}"'
        f' viewBox="0 0 {size} {size}" font-family="{FONT}">',
        f'<circle cx="{_fmt(c)}" cy="{_fmt(c)}" r="{_fmt(c)}" fill="#000000"/>',
    ]
    if bezel is not None:
        sw = DASH_BEZEL_DP * k
        r = c - (DASH_BEZEL_INSET_DP + DASH_BEZEL_DP / 2) * k
        parts.append(_arc(c, c, r, 360, bezel.track, sw))
        sweep = min(max(bezel.fraction, 0.0), 1.0) * 360.0
        if sweep > 0:
            parts.append(_arc(c, c, r, sweep, bezel.accent, sw))
    for e in col:
        h = _height(e) * k
        if isinstance(e, Text):
            parts += _text_svg(e, c, y, k)
        elif isinstance(e, StatRow):
            gap = 6 * k
            lw = len(e.label.s) * e.label.sp * CHAR_W * k
            vw = len(e.value.s) * e.value.sp * CHAR_W * k
            left = c - (lw + gap + vw) / 2
            parts += _text_svg(e.label, left, y + (h - e.label.sp * LINE * k) / 2, k, "start")
            parts += _text_svg(
                e.value, left + lw + gap, y + (h - e.value.sp * LINE * k) / 2, k, "start"
            )
        elif isinstance(e, Chips):
            w = DASH_CHIP_DP * k
            left = c - w * len(e.items) / 2
            for i, (v, lab) in enumerate(e.items):
                cx = left + w * i + w / 2
                parts += _text_svg(v, cx, y, k)
                parts += _text_svg(lab, cx, y + v.sp * LINE * k, k)
        elif isinstance(e, Ring):
            r = (RING_DP - RING_THICK_DP) / 2 * k
            cy = y + h / 2
            sw = RING_THICK_DP * k
            parts.append(_arc(c, cy, r, 360, TRACK, sw))
            sweep = min(max(e.fraction, 0.0), 1.0) * 360.0
            if sweep > 0:
                parts.append(_arc(c, cy, r, sweep, e.accent, sw))
            inner = [e.center] + ([e.label] if e.label else [])
            ih = sum(_height(t) for t in inner) * k
            ty = cy - ih / 2
            for t in inner:
                parts += _text_svg(t, c, ty, k)
                ty += _height(t) * k
        y += h
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def render(
    result: ParseResult,
    now: datetime,
    state: str | None = None,
    size: int = DEFAULT_SIZE,
    fetched_at: datetime | None = None,
) -> str:
    """SVG for a parsed feed. ``state`` None picks fresh/stale/malformed/unsupported
    from the document and the clock; a given state forces that screen."""
    fetched = fetched_at or now
    if state is None:
        if result.outcome == "ok" and result.feed is not None:
            state = display_state(result.feed, fetched, now)
        else:
            state = result.outcome
    if state not in STATES:
        raise ValueError(f"unknown state {state!r}")
    if state in ("fresh", "stale"):
        if result.feed is None:
            raise ValueError(f"cannot show {state}: feed is {result.outcome}")
        feed = result.feed
        if state == display_state(feed, fetched, now):
            foot = footer(feed, fetched, now)
        else:  # forced state: same label, prefix as in feed.footer()
            label = age_label(age(feed, fetched, now))
            foot = label if state == "fresh" else f"Old data · {label}"
        if feed.kind == "dashboard":
            col, bezel = dashboard_column(feed, foot, dimmed=state == "stale")
            return render_elements(col, size, bezel)
        col = feed_column(feed, foot, dimmed=state == "stale")
    elif state == "malformed":
        errs = result.errors or result.problems
        reason = errs[0].message if errs else "not a valid v1 feed"
        col = message_column(HEADLINES[state], truncate(reason, MALFORMED_REASON_MAX))
    else:
        col = message_column(HEADLINES[state], None)
    return render_elements(col, size)
