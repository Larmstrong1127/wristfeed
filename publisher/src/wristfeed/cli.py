"""Command-line interface: ``wristfeed validate | build | publish | preview``.

See SPEC.md, Publisher CLI.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import urllib.parse
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from wristfeed.feed import KINDS, STATUSES, format_utc, parse, parse_bytes, parse_rfc3339
from wristfeed.preview import DEFAULT_SIZE, STATES, render

SECRET_GIST_WARNING = (
    "warning: a secret gist is unlisted, not private. Anyone with the URL can read it; "
    "never put secrets in a feed."
)


class UsageError(Exception):
    """A bad command-line value; printed without a traceback, exit code 2."""


# ------------------------------------------------------------------ validate


def cmd_validate(args: argparse.Namespace) -> int:
    """Exit 0 only for a strictly valid v1 feed; fallbacks count as problems."""
    result = parse_bytes(Path(args.file).read_bytes())
    if result.is_strictly_valid:
        print(f"{args.file}: valid WristFeed v1 feed")
        return 0
    print(f"{args.file}: {result.outcome}" if result.outcome != "ok" else f"{args.file}: problems")
    for problem in result.problems:
        print(f"  {problem}")
    return 1


# --------------------------------------------------------------------- build


def parse_row_arg(text: str) -> dict[str, str]:
    """``Label=Value[:status]``. The status suffix is taken only if it is a known status,
    so values like ``12:30`` survive intact."""
    label, sep, rest = text.partition("=")
    if not sep or not label:
        raise UsageError(f"--row {text!r}: expected Label=Value[:status]")
    value, colon, status = rest.rpartition(":")
    if colon and status in STATUSES:
        return {"label": label, "value": value, "status": status}
    return {"label": label, "value": rest}


def _number(text: str) -> int | float:
    n = float(text)
    return int(n) if n.is_integer() else n


def parse_progress_arg(text: str) -> dict[str, Any]:
    """``Label=V/MAX`` with numeric V and MAX > 0."""
    label, sep, rest = text.partition("=")
    value, slash, mx = rest.partition("/")
    try:
        if not sep or not slash:
            raise ValueError
        v, m = _number(value), _number(mx)
    except ValueError:
        raise UsageError(f"--progress {text!r}: expected Label=V/MAX") from None
    if m <= 0:
        raise UsageError(f"--progress {text!r}: MAX must be > 0")
    return {"label": label, "value": v, "max": m}


def build_feed(args: argparse.Namespace, now: datetime | None = None) -> dict[str, Any]:
    doc: dict[str, Any] = {"wristfeed": 1, "title": args.title}
    if args.status:
        doc["status"] = args.status
    if args.kind:
        doc["kind"] = args.kind
    if args.row:
        doc["rows"] = [parse_row_arg(r) for r in args.row]
    if args.progress:
        doc["progress"] = parse_progress_arg(args.progress)
    if args.message is not None:
        doc["message"] = args.message
    doc["updated"] = format_utc(now or datetime.now(timezone.utc))
    if args.stale_after is not None:
        doc["stale_after_minutes"] = args.stale_after
    if args.tap_url is not None:
        doc["tap_url"] = args.tap_url
    return doc


def cmd_build(args: argparse.Namespace) -> int:
    doc = build_feed(args)
    result = parse(doc)
    if not result.is_strictly_valid:
        for problem in result.problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1
    text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(text)
    return 0


# ------------------------------------------------------------------- publish


def _run_gh(argv: list[str], stdin: str | None = None) -> subprocess.CompletedProcess[str]:
    if shutil.which("gh") is None:
        raise UsageError("the GitHub CLI 'gh' is not on PATH (https://cli.github.com)")
    return subprocess.run(
        ["gh", *argv], input=stdin, capture_output=True, text=True, encoding="utf-8", check=False
    )


def parse_gist_url(gist_url: str) -> tuple[str | None, str]:
    """``https://gist.github.com/[USER/]ID[/]`` -> ``(user or None, id)``.

    The id is always the last path segment; the user is known only when the path
    has exactly two segments (gh may print either form)."""
    text = gist_url.strip().split("?", 1)[0].split("#", 1)[0].rstrip("/")
    _, sep, rest = text.partition("://")
    path = (rest if sep else text).split("/")[1:]  # drop the host
    segments = [s for s in path if s]
    if not segments:
        raise ValueError(f"no gist id in {gist_url!r}")
    user = segments[-2] if len(segments) == 2 else None
    return user, segments[-1]


def raw_url(gist_url: str, filename: str) -> str | None:
    """The stable raw URL of ``filename`` (no revision hash, so it always serves the
    latest version).

    gh usually prints ``https://gist.github.com/<id>`` with no owner; GitHub
    redirects ``gist.github.com/<id>/raw/<file>`` to the raw file, so that form
    works without knowing the owner (second review, 2026-11-15). The file name is
    percent-encoded so a name with spaces still fetches.
    """
    user, gist_id = parse_gist_url(gist_url)
    name = urllib.parse.quote(filename)
    if user is None:
        return f"https://gist.github.com/{gist_id}/raw/{name}"
    return f"https://gist.githubusercontent.com/{user}/{gist_id}/raw/{name}"


def _gist_url_from_output(stdout: str) -> str | None:
    """The last line of gh's output that looks like a URL."""
    for line in reversed(stdout.strip().splitlines()):
        line = line.strip()
        if line.startswith(("https://", "http://")):
            return line
    return None


def cmd_publish(args: argparse.Namespace) -> int:
    path = Path(args.file)
    result = parse_bytes(path.read_bytes())
    if not result.is_strictly_valid:
        print(
            f"error: {path} is not a valid feed; run 'wristfeed validate {path}'", file=sys.stderr
        )
        return 1
    filename = args.filename or path.name
    print(SECRET_GIST_WARNING, file=sys.stderr)

    if args.create:
        # --filename only names stdin content, so the file goes in on stdin ("-").
        proc = _run_gh(
            ["gist", "create", "--filename", filename, "-"],
            stdin=path.read_text(encoding="utf-8-sig"),
        )
        if proc.returncode != 0:
            print(proc.stderr.strip(), file=sys.stderr)
            return proc.returncode
        gist_url = _gist_url_from_output(proc.stdout)
        if gist_url is None:
            print(
                f"error: could not find the gist URL in gh output: {proc.stdout!r}", file=sys.stderr
            )
            return 1
        try:
            raw = raw_url(gist_url, filename)
        except ValueError:
            raw = None
        if raw is None:
            print(gist_url)
            print(
                f"open the gist, click Raw on {filename}, and use that URL "
                "without the revision hash",
                file=sys.stderr,
            )
        else:
            print(raw)
        return 0

    proc = _run_gh(["gist", "edit", args.gist, "--filename", filename, str(path)])
    if proc.returncode != 0:
        print(proc.stderr.strip(), file=sys.stderr)
        return proc.returncode
    print(f"updated gist {args.gist} file {filename}")
    return 0


# ------------------------------------------------------------------- preview


def cmd_preview(args: argparse.Namespace) -> int:
    """Write an SVG of the round tile exactly as TileLayouts.kt lays it out."""
    if args.now is None:
        now = datetime.now(timezone.utc)
    else:
        parsed = parse_rfc3339(args.now)
        if parsed is None:
            raise UsageError(f"--now must be RFC 3339, got {args.now!r}")
        now = parsed
    if args.size < 100:
        raise UsageError("--size must be at least 100")
    result = parse_bytes(Path(args.file).read_bytes())
    try:
        svg = render(result, now, state=args.state, size=args.size)
    except ValueError as exc:
        raise UsageError(str(exc)) from exc
    Path(args.out).write_text(svg, encoding="utf-8", newline="\n")
    print(f"wrote {args.out}")
    return 0


# ---------------------------------------------------------------------- main


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="wristfeed", description="WristFeed v1 feed tools.")
    sub = p.add_subparsers(dest="command", required=True)

    v = sub.add_parser("validate", help="check a feed file against WristFeed v1")
    v.add_argument("file")
    v.set_defaults(func=cmd_validate)

    b = sub.add_parser("build", help="write a valid feed with updated=now")
    b.add_argument("--title", required=True)
    b.add_argument("--status", choices=STATUSES)
    b.add_argument("--kind", choices=KINDS)
    b.add_argument("--row", action="append", metavar="Label=Value[:status]")
    b.add_argument("--progress", metavar="Label=V/MAX")
    b.add_argument("--message")
    b.add_argument("--stale-after", type=int, metavar="N")
    b.add_argument("--tap-url", metavar="U")
    b.add_argument("--out", metavar="FILE")
    b.set_defaults(func=cmd_build)

    pub = sub.add_parser("publish", help="update a gist's file through gh")
    pub.add_argument("file")
    target = pub.add_mutually_exclusive_group(required=True)
    target.add_argument("--gist", metavar="ID", help="existing gist to update")
    target.add_argument(
        "--create", action="store_true", help="create a NEW secret gist and print its raw URL"
    )
    pub.add_argument("--filename", metavar="NAME", help="file name inside the gist")
    pub.set_defaults(func=cmd_publish)

    pv = sub.add_parser("preview", help="render the watch tile for a feed as an SVG")
    pv.add_argument("file")
    pv.add_argument("--out", required=True, metavar="FILE")
    pv.add_argument("--state", choices=STATES, help="force a screen (default: from feed + clock)")
    pv.add_argument("--now", metavar="ISO", help="clock for the age footer (default: now)")
    pv.add_argument("--size", type=int, default=DEFAULT_SIZE, metavar="PX")
    pv.set_defaults(func=cmd_preview)
    return p


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return int(args.func(args))
    except UsageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
