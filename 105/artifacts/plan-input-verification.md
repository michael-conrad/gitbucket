# Plan Input Verification Ledger — Issue 105

Written once; all subsequent plan composition reads THIS file, not the sources.

## Issue State (from local issue.yaml — canonical)

- title: `[SPEC-FIX] Move container to job level in upstream-release-watch workflow`
- status: open
- labels: `approved-for-pr` (authorization scope `for_pr`; `spec-cleared` to be appended by this task — full array preserved)

## SC List with Evidence Types (from spec.md)

| SC | Criterion (short) | Evidence | Type |
|----|-------------------|----------|------|
| SC-1 | Job-level `container:` on release-watch job, image exactly `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` | pytest in test/test_workflow_jobs.py | behavioral: test execution |
| SC-2 | No step-level `container:`/`entrypoint:` keys in any release-watch step | pytest in test/test_workflow_jobs.py | behavioral: test execution |
| SC-3 | Live `gh workflow run ... --ref dev` accepted, exit 0, no HTTP 422 | live run inspection | behavioral: live run inspection |
| SC-4 | Dispatched run conclusion `success` | `gh run view` observation | behavioral: live run inspection |
| SC-5 | State file not current → run files exactly one issue titled with received tag | `gh issue list` diff | behavioral: live observation |
| SC-6 | `run_release_watch()` advances temp state file to received tag | pytest on scripts/release-watch.py | behavioral: test execution |
| SC-7 | State current / dupe issue exists → run files zero new issues | second dispatch + `gh issue list` diff | behavioral: live observation |

## Structure Artifact Mappings (from structure.yaml)

| Phase | Name | SCs | TDD cycle |
|-------|------|-----|-----------|
| phase-1 | workflow-restructure | SC-1, SC-2 | RED/GREEN/verify/commit (both SCs share one RED test) |
| phase-2 | live-dispatch-accepted | SC-3 | verify/commit (live) |
| phase-3 | run-conclusion-success | SC-4 | verify/commit (live) |
| phase-4 | issue-filed-once | SC-5 | verify/commit (live) |
| phase-5 | state-advance-pytest | SC-6 | RED/GREEN/verify/commit |
| phase-6 | zero-issues-idempotency | SC-7 | verify/commit (live) |

DAG edges: 1→2→3→4→6; 1→5. No cycles. No SC's RED depends on a later phase's output.

Triplet colocation: SC-1/SC-2 red+green+commit in phase-1; SC-6 red+green+commit in phase-5; SC-3/4/5/7 live-verify + commit in phases 2/3/4/6 respectively. All ok.

## CLI Surface Flags Needed

- `./.opencode/tools/local-issues update gitbucket#105 --labels approved-for-pr,spec-cleared` (tool replaces whole labels array; use comma-joined list)
- Pre-cleanup globs per implementation-workflow reference card Artifact Retention Rule 3 (`rm -f {project_root}/tmp/105/artifacts/pipeline-<step>-*`)

## Affected Files

- `.github/workflows/upstream-release-watch.yml`
- `test/test_workflow_jobs.py`
- `scripts/release-watch.py` — read-only (unchanged; SC-6 test target)