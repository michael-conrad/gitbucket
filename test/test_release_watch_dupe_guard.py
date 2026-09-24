"""Enforcement tests for .issues/101 SC-7 — pre-filing dupe search.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-7 requires the release-watch "
            "dupe-guard pipeline to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc7_already_filed_tag_is_skipped(monkeypatch, tmp_path) -> None:
    """SC-7: a pre-filing dupe search keyed on the release tag skips filing
    for any tag already filed — an existing issue (open or closed) in
    michael-conrad/gitbucket whose title contains the tag."""
    module = load_script_module()

    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("", encoding="utf-8")

    monkeypatch.setattr(module, "fetch_upstream_latest_tag", lambda: "4.48.0")

    def fake_search_issue_exists(tag: str, repo: str) -> bool:
        # Search issues API: tag string in issue title, open OR closed.
        assert repo == "michael-conrad/gitbucket"
        return tag == "4.48.0"

    monkeypatch.setattr(
        module, "search_issue_exists", fake_search_issue_exists, raising=False
    )

    created = []

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        created.append({"title": title, "repo": repo})
        return {"number": len(created)}

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    receipts = module.run_release_watch(
        state_file=state_file, repo="michael-conrad/gitbucket"
    )

    assert not created, (
        f"already-filed tag 4.48.0 must NOT be re-filed, but issues were "
        f"created: {created!r}"
    )
    assert receipts == [], f"skipped tag must produce no receipts, got {receipts!r}"
