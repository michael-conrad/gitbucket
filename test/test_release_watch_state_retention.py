"""Enforcement tests for .issues/101 SC-10 — state retention across workflow runs.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
import shutil
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-10 requires the release-watch "
            "state retention to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc10_fresh_run_reads_committed_state_file(tmp_path, monkeypatch) -> None:
    """SC-10: a fresh workflow run (new checkout) with a committed state file
    present reads the last-seen TAG from that file."""
    module = load_script_module()

    committed_state = tmp_path / "committed" / "last-seen-tag"
    committed_state.parent.mkdir(parents=True)
    committed_state.write_text("4.48.0\n", encoding="utf-8")

    fresh_checkout = tmp_path / "fresh"
    fresh_checkout.mkdir()
    shutil.copy(committed_state, fresh_checkout / "last-seen-tag")

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.48.0", raising=False
    )
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)

    created = []

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        created.append({"title": title, "repo": repo})
        return {
            "number": 1,
            "html_url": f"https://github.com/{repo}/issues/1",
        }

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    state_file = fresh_checkout / "last-seen-tag"
    module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")

    assert len(created) == 0, (
        "fresh run with committed state 4.48.0 and upstream 4.48.0 must file "
        "zero issues — the committed state file must be read correctly"
    )


def test_sc10_no_release_run_leaves_state_unchanged(tmp_path, monkeypatch) -> None:
    """SC-10: a no-release run (upstream tag equal to last-seen) leaves the
    committed state file unchanged."""
    module = load_script_module()
    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.48.0\n", encoding="utf-8")
    original_bytes = state_file.read_bytes()

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.48.0", raising=False
    )
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)

    def failing_create_issue(title: str, body: str, repo: str) -> dict:
        pytest.fail("no-release run must not file any issue")
        raise AssertionError("unreachable: pytest.fail always raises")

    monkeypatch.setattr(module, "create_issue", failing_create_issue)

    receipts = module.run_release_watch(
        state_file=state_file, repo="michael-conrad/gitbucket"
    )

    assert receipts == [], "no-release run must return zero filing receipts"
    assert state_file.read_bytes() == original_bytes, (
        "no-release run must leave the committed state file unchanged — "
        "state persists across workflow runs"
    )
