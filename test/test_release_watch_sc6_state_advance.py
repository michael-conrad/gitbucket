"""Enforcement test for .issues/105 SC-6 — in-run state file advance.

SC-6: After the pipeline files issue(s), the in-run state file
`.github/release-watch/last-seen-tag` is updated to the newest filed release
tag. This test executes `run_release_watch()` against a temporary state file
with the upstream fetch mocked offline per the interface-compatibility
mock-boundary (compute_new_tags / search_issue_exists / create_issue are the
external calls).

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-6 requires the release-watch "
            "pipeline to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch_sc6", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc6_run_release_watch_advances_temp_state_file_to_received_tag(
    tmp_path, monkeypatch
) -> None:
    """SC-6: run_release_watch() advances a temporary state file to the
    received (upstream) tag after filing, with the upstream fetch mocked
    offline per the interface-compatibility artifact mock-boundary."""
    module = load_script_module()
    state_file = tmp_path / "release-watch" / "last-seen-tag"
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text("4.47.0\n", encoding="utf-8")

    upstream_tag = "4.48.0"

    # Mock boundary: upstream fetch (compute_new_tags external call)
    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", lambda: upstream_tag, raising=False
    )
    # Mock boundary: GitHub dupe search
    monkeypatch.setattr(module, "search_issue_exists", lambda tag, repo="": False)

    # Mock boundary: GitHub issue filing — returns a confirmed receipt
    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        return {"number": 1, "html_url": f"https://github.com/{repo}/issues/1"}

    monkeypatch.setattr(module, "create_issue", fake_create_issue)
    monkeypatch.setattr(module, "DEFAULT_STATE_PATH", state_file, raising=False)

    receipts = module.run_release_watch(
        state_file=state_file, repo="michael-conrad/gitbucket"
    )

    assert receipts, "filing must produce at least one receipt for the new tag"
    assert state_file.exists(), (
        "SC-6: run_release_watch must create/advance the in-run state file"
    )
    content = state_file.read_text(encoding="utf-8")
    assert content == f"{upstream_tag}\n", (
        f"SC-6: state file must advance to the received tag {upstream_tag!r} "
        f"(plain text single tag line + trailing newline), got {content!r}"
    )