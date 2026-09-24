"""Enforcement tests for .issues/101 SC-8 — post-receipt state persistence.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-8 requires the release-watch "
            "state persistence to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc8_failed_filing_leaves_state_unchanged(tmp_path, monkeypatch) -> None:
    """SC-8: a failed filing must NOT advance the last-seen TAG state file."""
    module = load_script_module()
    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.47.0\n", encoding="utf-8")

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.48.0", raising=False
    )
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)
    monkeypatch.setattr(module, "DEFAULT_STATE_PATH", state_file, raising=False)

    def failing_create_issue(title: str, body: str, repo: str) -> dict:
        raise RuntimeError("filing failed — GitHub API error")

    monkeypatch.setattr(module, "create_issue", failing_create_issue)

    with pytest.raises(RuntimeError):
        module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")

    assert state_file.read_text(encoding="utf-8").strip() == "4.47.0", (
        "state must NOT advance when filing fails — last-seen TAG is only "
        "persisted after a confirmed filing receipt"
    )


def test_sc8_successful_filing_advances_state(tmp_path, monkeypatch) -> None:
    """SC-8: after a successful filing receipt, the state file contains the
    filed tag."""
    module = load_script_module()
    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.47.0\n", encoding="utf-8")

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.48.0", raising=False
    )
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)
    monkeypatch.setattr(module, "DEFAULT_STATE_PATH", state_file, raising=False)

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        return {
            "number": 1,
            "html_url": f"https://github.com/{repo}/issues/1",
        }

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")

    assert state_file.read_text(encoding="utf-8").strip() == "4.48.0", (
        "state file must contain the filed tag after a confirmed filing receipt"
    )


def test_sc8_absent_state_bootstraps_by_filing_latest_then_advancing(
    tmp_path, monkeypatch
) -> None:
    """SC-8: absent/empty state file — bootstrap files the current latest tag
    then advances state to it."""
    module = load_script_module()
    state_file = tmp_path / "last-seen-tag"  # absent

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.48.0", raising=False
    )
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)
    monkeypatch.setattr(module, "DEFAULT_STATE_PATH", state_file, raising=False)

    created = []

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        created.append({"title": title, "repo": repo})
        return {
            "number": len(created),
            "html_url": f"https://github.com/{repo}/issues/{len(created)}",
        }

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")

    assert len(created) == 1, (
        f"bootstrap must file the current latest tag, filed {len(created)} issues"
    )
    assert "4.48.0" in created[0]["title"], (
        f"bootstrap must file the current latest tag 4.48.0, got "
        f"{created[0]['title']!r}"
    )
    assert state_file.exists() and state_file.read_text(encoding="utf-8").strip() == (
        "4.48.0"
    ), "bootstrap must advance state to the filed tag after the receipt"
