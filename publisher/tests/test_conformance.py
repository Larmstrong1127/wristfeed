from __future__ import annotations

import json
from pathlib import Path

import pytest
from conftest import corpus_cases

from wristfeed.feed import parse_bytes


@pytest.mark.parametrize("case", corpus_cases(), ids=lambda p: p.stem)
def test_corpus_case(case: Path) -> None:
    expected = json.loads(case.with_name(case.stem + ".expected.json").read_text("utf-8"))
    result = parse_bytes(case.read_bytes())
    assert result.outcome == expected["outcome"]
    assert bool(result.warnings) == expected["fallback"]
    if expected["outcome"] == "ok":
        assert result.feed is not None
        assert result.feed.to_dict() == expected["feed"]
    else:
        assert result.feed is None
        assert "feed" not in expected


def test_corpus_names_match_outcomes() -> None:
    for case in corpus_cases():
        expected = json.loads(case.with_name(case.stem + ".expected.json").read_text("utf-8"))
        family = case.stem.split("-")[0]
        want = {"valid": ("ok", False), "fallback": ("ok", True)}.get(family)
        if want:
            assert (expected["outcome"], expected["fallback"]) == want, case.name
        else:
            assert expected["outcome"] == family, case.name


def test_corpus_is_not_empty() -> None:
    assert len(corpus_cases()) >= 20
