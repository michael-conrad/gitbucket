"""Enforcement tests for .issues/101 SC-2 — workflow overlap serialization.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

from pathlib import Path

import pytest
import yaml

WORKFLOW_PATH = Path(".github/workflows/upstream-release-watch.yml")


def test_sc2_workflow_concurrency_group() -> None:
    """SC-2: workflow-level concurrency group serializes overlapping runs."""
    if not WORKFLOW_PATH.exists():
        pytest.fail(f"workflow file missing: {WORKFLOW_PATH}")
    data = yaml.safe_load(WORKFLOW_PATH.read_text())
    assert "concurrency" in data, "no workflow-level concurrency group declared"
    concurrency = data["concurrency"]
    group = concurrency["group"] if isinstance(concurrency, dict) else concurrency
    assert isinstance(group, str) and group.strip(), (
        "concurrency group not a named group"
    )
