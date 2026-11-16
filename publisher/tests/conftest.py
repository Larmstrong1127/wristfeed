from __future__ import annotations

from pathlib import Path

CONFORMANCE_DIR = Path(__file__).resolve().parents[2] / "conformance"


def corpus_cases() -> list[Path]:
    """Every feed body in the shared corpus (excluding the expectation files)."""
    return sorted(
        p for p in CONFORMANCE_DIR.glob("*.json") if not p.name.endswith(".expected.json")
    )
