"""Enforcement tests for .github/workflows/upstream-release-watch.yml
(.issues/103 SC-1..3; .issues/105 SC-1, SC-2 container placement).

Co-authored with AI: OpenCode (ollama-cloud/glm-5.3-flash)
"""

from pathlib import Path

import pytest
import yaml

WORKFLOW_PATH = Path(".github/workflows/upstream-release-watch.yml")


def load_workflow() -> dict:
    if not WORKFLOW_PATH.exists():
        pytest.fail(f"workflow file missing: {WORKFLOW_PATH}")
    return yaml.safe_load(WORKFLOW_PATH.read_text())


def test_sc1_jobs_section_has_at_least_one_job() -> None:
    """SC-1 (.issues/103): workflow declares a jobs: section with >= 1 job."""
    data = load_workflow()
    jobs = data.get("jobs")
    assert jobs, "workflow declares no jobs: section"
    assert isinstance(jobs, dict), "jobs: must be a mapping of job id -> definition"
    assert len(jobs) >= 1, "jobs: section is empty — no job defined"
    for job_id, job in jobs.items():
        assert isinstance(job, dict), f"job {job_id!r} is not a mapping"
        assert "steps" in job or "uses" in job, f"job {job_id!r} defines no steps"


def test_sc2_checkout_step_precedes_script_invocation() -> None:
    """SC-2 (.issues/103): job contains an actions/checkout@* step before any
    script-invocation step (steps that reference scripts/release-watch.py)."""
    data = load_workflow()
    jobs = data.get("jobs") or {}
    assert jobs, "workflow declares no jobs: section"
    for job_id, job in jobs.items():
        steps = job.get("steps")
        assert steps, f"job {job_id!r} defines no steps"
        checkout_idx = None
        for idx, step in enumerate(steps):
            uses = step.get("uses", "") if isinstance(step, dict) else ""
            if uses.startswith("actions/checkout@"):
                checkout_idx = idx
                break
        assert checkout_idx is not None, (
            f"job {job_id!r} has no actions/checkout@* step"
        )
        after = steps[checkout_idx + 1 :]
        assert after, f"job {job_id!r} has no steps after checkout"
        assert any(
            isinstance(step, dict) and "release-watch.py" in (step.get("run") or "")
            for step in after
        ), (
            f"job {job_id!r} has no step after actions/checkout@* that invokes "
            "release-watch.py"
        )


def test_sc3_job_declares_issue_and_contents_write_permissions() -> None:
    """SC-3 (.issues/103): job declares permissions with exactly issues: write
    and contents: write — no broader scopes present."""
    data = load_workflow()
    jobs = data.get("jobs") or {}
    assert jobs, "workflow declares no jobs: section"
    for job_id, job in jobs.items():
        permissions = job.get("permissions")
        assert permissions, f"job {job_id!r} declares no permissions:"
        assert permissions.get("issues") == "write", (
            f"job {job_id!r} does not declare issues: write"
        )
        assert permissions.get("contents") == "write", (
            f"job {job_id!r} does not declare contents: write"
        )


def test_sc1_job_level_container_on_release_watch() -> None:
    """SC-1 (.issues/105): release-watch job declares a job-level `container:`
    whose image is exactly ghcr.io/astral-sh/uv:python3.12-bookworm-slim."""
    data = load_workflow()
    jobs = data.get("jobs") or {}
    assert jobs, "workflow declares no jobs: section"
    expected_image = "ghcr.io/astral-sh/uv:python3.12-bookworm-slim"
    job = jobs.get("release-watch")
    assert job, "workflow declares no release-watch job"
    container = job.get("container")
    assert container, (
        "release-watch job does not declare a job-level container:"
    )
    image = container.get("image", "") if isinstance(container, dict) else str(container)
    assert image == expected_image, (
        f"release-watch job container image is {image!r}, "
        f"expected {expected_image!r}"
    )


def test_sc2_no_step_level_container_or_entrypoint() -> None:
    """SC-2 (.issues/105): every step in the release-watch job carries neither
    a `container:` nor an `entrypoint:` key — container config lives at the
    job level only."""
    data = load_workflow()
    jobs = data.get("jobs") or {}
    assert jobs, "workflow declares no jobs: section"
    job = jobs.get("release-watch")
    assert job, "workflow declares no release-watch job"
    steps = job.get("steps")
    assert steps, "release-watch job defines no steps"
    for idx, step in enumerate(steps):
        assert isinstance(step, dict), f"step {idx} is not a mapping"
        assert "container" not in step, (
            f"step {idx} in release-watch job declares a step-level "
            "`container:` key — container must be job-level"
        )
        assert "entrypoint" not in step, (
            f"step {idx} in release-watch job declares an `entrypoint:` key — "
            "container config must be job-level"
        )
