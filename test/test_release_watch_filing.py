"""Enforcement tests for .issues/101 SC-5 — ticket filing.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

import importlib.util
from pathlib import Path

import pytest

SCRIPT_PATH = Path("scripts/release-watch.py")


def load_script_module():
    if not SCRIPT_PATH.exists():
        pytest.fail(
            f"script missing: {SCRIPT_PATH} — SC-5 requires the release-watch "
            "filing pipeline to exist"
        )
    spec = importlib.util.spec_from_file_location("release_watch", SCRIPT_PATH)
    assert spec is not None
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_sc5_one_issue_created_per_new_tag(monkeypatch) -> None:
    """SC-5: for each new release tag, the job files exactly one spec issue
    in michael-conrad/gitbucket via the GitHub API."""
    module = load_script_module()

    created = []

    def fake_create_issue(title: str, body: str, repo: str) -> dict:
        created.append({"title": title, "body": body, "repo": repo})
        number = len(created)
        return {
            "number": number,
            "html_url": f"https://github.com/{repo}/issues/{number}",
        }

    monkeypatch.setattr(module, "create_issue", fake_create_issue)

    filed = module.file_new_tags(["4.48.0"], repo="michael-conrad/gitbucket")

    assert len(created) == 1, (
        f"expected exactly one issue filed for one new tag, got {len(created)}: "
        f"{created!r}"
    )
    assert created[0]["repo"] == "michael-conrad/gitbucket"
    assert "4.48.0" in created[0]["title"], (
        f"issue title must be keyed on the release tag, got {created[0]['title']!r}"
    )
    assert filed and all("number" in receipt for receipt in filed), (
        f"filing must return confirmed receipts, got {filed!r}"
    )
