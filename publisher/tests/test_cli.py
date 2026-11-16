"""CLI tests through main(argv); gh is always mocked, nothing touches the network."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from wristfeed import cli
from wristfeed.cli import main, parse_gist_url, parse_progress_arg, parse_row_arg, raw_url
from wristfeed.feed import parse

VALID = {"wristfeed": 1, "title": "JARVIS", "updated": "2026-11-16T06:40:00Z"}


def write(tmp_path: Path, doc: Any, name: str = "feed.json") -> Path:
    p = tmp_path / name
    p.write_text(json.dumps(doc) if not isinstance(doc, str) else doc, encoding="utf-8")
    return p


# ------------------------------------------------------------------ validate


def test_validate_ok(tmp_path: Path, capsys) -> None:
    assert main(["validate", str(write(tmp_path, VALID))]) == 0
    assert "valid" in capsys.readouterr().out


@pytest.mark.parametrize(
    ("doc", "needle"),
    [
        ({**VALID, "title": None}, "title"),
        ({**VALID, "wristfeed": 2}, "unsupported"),
        ({**VALID, "status": "nope"}, "status"),  # a fallback is still a problem
        ("{oops", "not valid JSON"),
    ],
)
def test_validate_problems_exit_1(tmp_path: Path, capsys, doc: Any, needle: str) -> None:
    assert main(["validate", str(write(tmp_path, doc))]) == 1
    assert needle in capsys.readouterr().out


def test_validate_missing_file_exit_2(tmp_path: Path) -> None:
    assert main(["validate", str(tmp_path / "nope.json")]) == 2


# --------------------------------------------------------------------- build


@pytest.mark.parametrize(
    ("arg", "row"),
    [
        ("Apps=1/5:bad", {"label": "Apps", "value": "1/5", "status": "bad"}),
        ("Asks=2", {"label": "Asks", "value": "2"}),
        ("Time=12:30", {"label": "Time", "value": "12:30"}),
        ("Time=12:30:ok", {"label": "Time", "value": "12:30", "status": "ok"}),
        ("Eq=a=b", {"label": "Eq", "value": "a=b"}),
        ("Empty=", {"label": "Empty", "value": ""}),
    ],
)
def test_parse_row_arg(arg: str, row: dict[str, str]) -> None:
    assert parse_row_arg(arg) == row


@pytest.mark.parametrize("arg", ["novalue", "=x"])
def test_parse_row_arg_rejects(arg: str) -> None:
    with pytest.raises(cli.UsageError):
        parse_row_arg(arg)


def test_parse_progress_arg() -> None:
    assert parse_progress_arg("Apps=1/5") == {"label": "Apps", "value": 1, "max": 5}
    assert parse_progress_arg("Km=2.5/10") == {"label": "Km", "value": 2.5, "max": 10}


@pytest.mark.parametrize("arg", ["Apps=1", "Apps=a/5", "Apps=1/0", "Apps=1/-2", "1/5"])
def test_parse_progress_arg_rejects(arg: str) -> None:
    with pytest.raises(cli.UsageError):
        parse_progress_arg(arg)


def test_build_writes_valid_feed(tmp_path: Path) -> None:
    out = tmp_path / "out.json"
    argv = [
        "build", "--title", "JARVIS", "--status", "warn", "--kind", "stats",
        "--row", "Apps=1/5:bad", "--row", "Asks=2", "--progress", "Apps=1/5",
        "--stale-after", "60", "--tap-url", "https://example.com", "--out", str(out),
    ]  # fmt: skip
    assert main(argv) == 0
    doc = json.loads(out.read_text("utf-8"))
    result = parse(doc)
    assert result.is_strictly_valid
    assert doc["rows"][0] == {"label": "Apps", "value": "1/5", "status": "bad"}
    assert doc["progress"] == {"label": "Apps", "value": 1, "max": 5}
    assert doc["stale_after_minutes"] == 60
    assert doc["updated"].endswith("Z")
    assert b"\r\n" not in out.read_bytes()


def test_build_to_stdout(capsys) -> None:
    assert main(["build", "--title", "Quiet", "--kind", "message", "--message", "All quiet"]) == 0
    doc = json.loads(capsys.readouterr().out)
    assert doc["message"] == "All quiet" and parse(doc).is_strictly_valid


@pytest.mark.parametrize(
    "extra",
    [["--stale-after", "0"], ["--tap-url", "http://x"], ["--title", " "], ["--row", "bad"]],
)
def test_build_refuses_invalid(extra: list[str]) -> None:
    assert main(["build", "--title", "T", *extra]) in (1, 2)


def test_build_rejects_unknown_status() -> None:
    with pytest.raises(SystemExit):
        main(["build", "--title", "T", "--status", "great"])


# ------------------------------------------------------------------- publish


class FakeGh:
    def __init__(self, stdout: str = "", returncode: int = 0) -> None:
        self.calls: list[list[str]] = []
        self.inputs: list[str | None] = []
        self.stdout, self.returncode = stdout, returncode

    def __call__(self, argv: list[str], **kw: Any) -> subprocess.CompletedProcess[str]:
        self.calls.append(argv)
        self.inputs.append(kw.get("input"))
        return subprocess.CompletedProcess(argv, self.returncode, self.stdout, "gh failed")


@pytest.fixture
def gh(monkeypatch: pytest.MonkeyPatch) -> FakeGh:
    fake = FakeGh()
    monkeypatch.setattr(cli.shutil, "which", lambda name: "gh")
    monkeypatch.setattr(cli.subprocess, "run", fake)
    return fake


def test_publish_edits_existing_gist(tmp_path: Path, gh: FakeGh, capsys) -> None:
    path = write(tmp_path, VALID)
    assert main(["publish", str(path), "--gist", "abc123"]) == 0
    assert gh.calls == [["gh", "gist", "edit", "abc123", "--filename", "feed.json", str(path)]]
    assert "unlisted, not private" in capsys.readouterr().err


def test_publish_custom_filename(tmp_path: Path, gh: FakeGh) -> None:
    path = write(tmp_path, VALID)
    assert main(["publish", str(path), "--gist", "abc", "--filename", "jarvis.json"]) == 0
    assert gh.calls[0][4:6] == ["--filename", "jarvis.json"]


def test_publish_create_prints_raw_url(tmp_path: Path, gh: FakeGh, capsys) -> None:
    gh.stdout = "- Creating gist feed.json\nhttps://gist.github.com/someone/deadbeef\n"
    path = write(tmp_path, VALID)
    assert main(["publish", str(path), "--create", "--filename", "w.json"]) == 0
    assert gh.calls == [["gh", "gist", "create", "--filename", "w.json", "-"]]
    assert gh.inputs == [path.read_text("utf-8")]
    assert "--public" not in gh.calls[0]
    captured = capsys.readouterr()
    assert captured.out.strip() == "https://gist.githubusercontent.com/someone/deadbeef/raw/w.json"
    assert "unlisted, not private" in captured.err


def test_publish_requires_gist_or_create(tmp_path: Path, gh: FakeGh) -> None:
    with pytest.raises(SystemExit):
        main(["publish", str(write(tmp_path, VALID))])
    assert gh.calls == []


def test_publish_gist_and_create_are_exclusive(tmp_path: Path, gh: FakeGh) -> None:
    with pytest.raises(SystemExit):
        main(["publish", str(write(tmp_path, VALID)), "--gist", "a", "--create"])
    assert gh.calls == []


def test_publish_refuses_invalid_feed(tmp_path: Path, gh: FakeGh) -> None:
    assert main(["publish", str(write(tmp_path, {"title": "x"})), "--gist", "a"]) == 1
    assert gh.calls == []


def test_publish_propagates_gh_failure(tmp_path: Path, gh: FakeGh) -> None:
    gh.returncode = 4
    assert main(["publish", str(write(tmp_path, VALID)), "--gist", "a"]) == 4


def test_publish_without_gh_on_path(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(cli.shutil, "which", lambda name: None)
    called = []
    monkeypatch.setattr(cli.subprocess, "run", lambda *a, **k: called.append(a))
    assert main(["publish", str(write(tmp_path, VALID)), "--gist", "a"]) == 2
    assert called == []


def test_raw_url() -> None:
    assert raw_url("https://gist.github.com/u/id/", "f.json") == (
        "https://gist.githubusercontent.com/u/id/raw/f.json"
    )
    assert raw_url("https://gist.github.com/id", "f.json") is None


@pytest.mark.parametrize(
    ("url", "user", "gist_id"),
    [
        ("https://gist.github.com/someone/deadbeef", "someone", "deadbeef"),
        ("https://gist.github.com/someone/deadbeef/", "someone", "deadbeef"),
        ("https://gist.github.com/deadbeef", None, "deadbeef"),
        ("https://gist.github.com/deadbeef/\n", None, "deadbeef"),
    ],
)
def test_parse_gist_url(url: str, user: str | None, gist_id: str) -> None:
    assert parse_gist_url(url) == (user, gist_id)


def test_publish_create_without_user_prints_gist_url(tmp_path: Path, gh: FakeGh, capsys) -> None:
    gh.stdout = "https://gist.github.com/deadbeef/\n"
    path = write(tmp_path, VALID)
    assert main(["publish", str(path), "--create", "--filename", "w.json"]) == 0
    captured = capsys.readouterr()
    assert captured.out.strip().rstrip("/") == "https://gist.github.com/deadbeef"
    assert "click Raw" in captured.err


def test_publish_create_trailing_slash_and_noise(tmp_path: Path, gh: FakeGh, capsys) -> None:
    gh.stdout = "https://gist.github.com/someone/deadbeef/\n✓ Created secret gist\n"
    path = write(tmp_path, VALID)
    assert main(["publish", str(path), "--create"]) == 0
    out = capsys.readouterr().out.strip()
    assert out == f"https://gist.githubusercontent.com/someone/deadbeef/raw/{path.name}"


def test_publish_create_without_url_fails(tmp_path: Path, gh: FakeGh) -> None:
    gh.stdout = "something odd\n"
    assert main(["publish", str(write(tmp_path, VALID)), "--create"]) == 1


# ------------------------------------------------------- real entry point


def test_module_entry_point_runs(tmp_path: Path) -> None:
    """Runs the real interpreter (no gh, no network) to prove `python -m wristfeed` works."""
    src = Path(__file__).resolve().parents[1] / "src"
    proc = subprocess.run(
        [sys.executable, "-m", "wristfeed", "validate", str(write(tmp_path, VALID))],
        capture_output=True,
        text=True,
        env={"PYTHONPATH": str(src), "SYSTEMROOT": __import__("os").environ.get("SYSTEMROOT", "")},
        check=False,
    )
    assert proc.returncode == 0, proc.stderr
