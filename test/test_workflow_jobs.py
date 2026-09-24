"""Enforcement test for .issues/103 SC-1 — jobs section with at least one job.

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
        for idx, step in enumerate(steps[checkout_idx + 1 :], start=checkout_idx + 1):
            run = step.get("run", "") if isinstance(step, dict) else ""
            assert "release-watch.py" not in run, (
                f"step {idx} in job {job_id!r} invokes release-watch.py before "
                "any actions/checkout@* step ran"
            )
        break


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


def test_sc4_script_invocation_container_step() -> None:
    """SC-4 (.issues/103): job has a container step with image
    ghcr.io/astral-sh/uv:python3.12-bookworm-slim (NFR-1) invoking
    scripts/release-watch.py."""
    data = load_workflow()
    jobs = data.get("jobs") or {}
    assert jobs, "workflow declares no jobs: section"
    expected_image = "ghcr.io/astral-sh/uv:python3.12-bookworm-slim"
    for job_id, job in jobs.items():
        steps = job.get("steps")
        assert steps, f"job {job_id!r} defines no steps"
        for step in steps:
            assert isinstance(step, dict), f"step in job {job_id!r} is not a mapping"
            container = step.get("container")
            if container is None:
                continue
            image = (
                container.get("image", "")
                if isinstance(container, dict)
                else str(container)
            )
            assert image == expected_image, (
                f"job {job_id!r} container image is {image!r}, "
                f"expected {expected_image!r} (NFR-1)"
            )
            run = step.get("run", "")
            entrypoint = ""
            if isinstance(container, dict):
                entrypoint = container.get("entrypoint", "")
            combined = f"{run} {entrypoint} {step.get('with', '')}"
            assert "scripts/release-watch.py" in combined, (
                f"job {job_id!r} container step does not invoke "
                "scripts/release-watch.py"
            )
            return
        pytest.fail(f"job {job_id!r} has no container step invoking release-watch")

    pytest.fail("workflow declares no jobs with a container step")
