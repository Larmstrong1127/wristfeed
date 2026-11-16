"""The shipped JSON Schema and the hand-written rules must agree on what is strictly valid."""

from __future__ import annotations

import json
from importlib import resources

import pytest
from conftest import corpus_cases

from wristfeed.feed import MAX_DEPTH, _nesting_depth, _parse_int, _reject_constant, parse

jsonschema = pytest.importorskip("jsonschema")

SCHEMA = json.loads(resources.files("wristfeed").joinpath("schema.json").read_text("utf-8"))
U = "2026-11-16T06:40:00Z"
BASE = {"wristfeed": 1, "title": "T", "updated": U}

EXTRA_DOCS = [
    BASE,
    {**BASE, "status": "ok"},
    {**BASE, "status": "OK"},
    {**BASE, "kind": "progress"},
    {**BASE, "kind": None},
    {**BASE, "rows": []},
    {**BASE, "rows": [{"label": "a", "value": "b", "status": None}]},
    {**BASE, "rows": [{"label": "a"}]},
    {**BASE, "progress": None},
    {**BASE, "progress": {"label": "a", "value": -3, "max": 2.5}},
    {**BASE, "progress": {"label": "a", "value": 1, "max": -1}},
    {**BASE, "progress": {"label": "a", "value": True, "max": 2}},
    {**BASE, "progress": {"value": 1, "max": 2}},
    {**BASE, "message": None},
    {**BASE, "message": ["x"]},
    {**BASE, "stale_after_minutes": 1},
    {**BASE, "stale_after_minutes": "60"},
    {**BASE, "stale_after_minutes": True},
    {**BASE, "tap_url": "HTTPS://x"},
    {**BASE, "tap_url": "ftp://x"},
    {**BASE, "tap_url": "https://"},
    {**BASE, "updated": "2026-11-16 06:40:00Z"},
    {**BASE, "updated": "2026-11-16T06:40:00.123456789+05:30"},
    {**BASE, "updated": "2026-11-16"},
    {**BASE, "title": ""},
    {**BASE, "wristfeed": 2},
    {**BASE, "wristfeed": True},
    {"title": "T", "updated": U},
    [],
    "feed",
]


def _corpus_docs() -> list[object]:
    docs = []
    for case in corpus_cases():
        try:
            text = case.read_bytes().decode("utf-8-sig")
            if _nesting_depth(text) > MAX_DEPTH:
                continue  # malformed before any schema applies (SPEC rule 6)
            # Decode exactly as parse_bytes does, so the schema sees the parser's input.
            docs.append(json.loads(text, parse_int=_parse_int, parse_constant=_reject_constant))
        except ValueError:  # not JSON, or NaN/Infinity (SPEC rule 6)
            continue
    return docs


@pytest.mark.parametrize("doc", _corpus_docs() + EXTRA_DOCS, ids=repr)
def test_schema_agrees_with_rules(doc: object) -> None:
    schema_valid = jsonschema.Draft202012Validator(SCHEMA).is_valid(doc)
    assert schema_valid == parse(doc).is_strictly_valid


def test_schema_is_a_valid_2020_12_schema() -> None:
    jsonschema.Draft202012Validator.check_schema(SCHEMA)
