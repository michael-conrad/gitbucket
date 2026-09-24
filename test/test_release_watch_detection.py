"""Enforcement tests for .issues/101 SC-3 — tag-diff detection pipeline.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-3 requires the release-watch "
            "detection pipeline to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc3_poll_diff_produces_new_tag_set(tmp_path, monkeypatch) -> None:
    """SC-3: poll upstream releases/latest -> tag_name, diff against persisted
    last-seen TAG, produce the set of NEW tags."""
    module = load_script_module()

    def fake_fetch_upstream_latest_tag() -> str:
        return "4.48.0"

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", fake_fetch_upstream_latest_tag
    )

    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.47.0\n")

    new_tags = module.compute_new_tags(state_file=state_file)

    assert new_tags == ["4.48.0"], (
        f"expected new-tag set {{'4.48.0'}}, got {new_tags!r}"
    )


def test_sc4_commit_hash_reference_yields_empty_new_tag_set(
    tmp_path, monkeypatch
) -> None:
    """SC-4: a commit-hash reference is never treated as a release — the
    detection path yields an empty new-tag set for non-tag values."""
    module = load_script_module()

    def fake_fetch_upstream_latest_tag() -> str:
        return "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", fake_fetch_upstream_latest_tag
    )

    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.47.0\n")

    new_tags = module.compute_new_tags(state_file=state_file)

    assert new_tags == [], (
        f"commit-hash reference must not be treated as a release, got {new_tags!r}"
    )


def test_sc3_unchanged_state_yields_empty_new_tag_set(tmp_path, monkeypatch) -> None:
    """SC-3: when the upstream tag equals the persisted tag, the new-tag set
    is empty (no new releases)."""
    module = load_script_module()

    def fake_fetch_upstream_latest_tag() -> str:
        return "4.48.0"

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", fake_fetch_upstream_latest_tag
    )

    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.48.0\n")

    new_tags = module.compute_new_tags(state_file=state_file)

    assert new_tags == [], f"expected empty new-tag set, got {new_tags!r}"
