"""Enforcement tests for .issues/101 SC-6 — repeat-run idempotency.

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
            "run pipeline to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc6_repeat_run_with_unchanged_tag_files_zero_issues(
    tmp_path, monkeypatch
) -> None:
    """SC-6: a repeated run with unchanged last-seen TAG files zero issues —
    two consecutive runs, zero filings on the second run."""
    module = load_script_module()

    def fake_fetch_upstream_latest_tag() -> str:
        return "4.48.0"

    monkeypatch.setattr(
        module, "fetch_upstream_latest_tag", fake_fetch_upstream_latest_tag
    )

    created = []

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        created.append({"title": title, "repo": repo})
        number = len(created)
        return {
            "number": number,
            "html_url": f"https://github.com/{repo}/issues/{number}",
        }

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    state_file = tmp_path / "last-seen-tag"
    state_file.write_text("4.48.0\n")

    module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")
    filings_first_run = len(created)
    module.run_release_watch(state_file=state_file, repo="michael-conrad/gitbucket")
    filings_second_run = len(created) - filings_first_run

    assert filings_second_run == 0, (
        f"repeat run with unchanged last-seen TAG must file zero issues, "
        f"got {filings_second_run} filings: {created!r}"
    )
