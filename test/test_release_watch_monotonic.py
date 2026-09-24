"""Enforcement tests for .issues/101 SC-9 — monotonic state advance.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-9 requires the release-watch "
            "monotonic state logic to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc9_older_tag_does_not_regress_persisted_state(tmp_path, monkeypatch) -> None:
    """SC-9: feeding an older tag after a newer one must NOT regress the
    persisted last-seen TAG state."""
    module = load_script_module()
    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.48.0\n", encoding="utf-8")

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: "4.47.0", raising=False
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
        "state must advance monotonically — an older tag (4.47.0) offered "
        "after a newer one (4.48.0) must never regress the persisted state"
    )
