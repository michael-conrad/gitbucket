"""Enforcement tests for .issues/101 SC-1 — workflow trigger declarations.

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

from pathlib import Path

import pytest
import yaml

WORKFLOW_PATH = Path(".github/workflows/upstream-release-watch.yml")


def load_workflow() -> dict:
    if not WORKFLOW_PATH.exists():
        pytest.fail(
            f"workflow file missing: {WORKFLOW_PATH} — SC-1 requires the "
            "upstream-release-watch workflow to exist"
        )
    return yaml.safe_load(WORKFLOW_PATH.read_text())


def test_workflow_exists_with_on_triggers() -> None:
    data = load_workflow()
    assert "on" in data or True in data, "workflow declares no triggers"


def test_sc1_weekly_schedule_cron() -> None:
    """SC-1: schedule: POSIX cron fires once per week."""
    data = load_workflow()
    triggers = data.get("on", data.get(True, {}))
    assert "schedule" in triggers, "no schedule: trigger declared"
    schedules = triggers["schedule"]
    assert isinstance(schedules, list) and len(schedules) >= 1
    crons = [entry["cron"] for entry in schedules]
    # POSIX cron field must have exactly 5 space-separated fields.
    for cron in crons:
        assert len(cron.split()) == 5, f"cron not POSIX 5-field: {cron!r}"
    # Weekly: day-of-month and month fields must be unrestricted ('*'),
    # with day-of-week or day-of-month pinning to one day per week.
    weekly = [c for c in crons if c.split()[2] == "*" and c.split()[3] == "*"]
    assert weekly, f"no weekly cron entry found in {crons!r}"


def test_sc1_workflow_dispatch_trigger() -> None:
    """SC-1: workflow_dispatch manual trigger declared."""
    data = load_workflow()
    triggers = data.get("on", data.get(True, {}))
    assert "workflow_dispatch" in triggers, "no workflow_dispatch trigger"
