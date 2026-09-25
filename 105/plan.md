---
plan_schema_version: 1
issue: 105
title: "Move container to job level in upstream-release-watch workflow"
authorization_scope: for_pr
pr_strategy: stacked
phase_count: 6
dispatch:
  - phase-1: pre-regression (test-driven-development), red (test-driven-development), green (test-driven-development), post-regression (test-driven-development), verify (verification-before-completion), commit-inline (orchestrator)
  - phase-2: verify (verification-before-completion), commit-inline (orchestrator)
  - phase-3: verify (verification-before-completion), commit-inline (orchestrator)
  - phase-4: verify (verification-before-completion), commit-inline (orchestrator)
  - phase-5: red (test-driven-development), green (test-driven-development), post-regression (test-driven-development), verify (verification-before-completion), commit-inline (orchestrator)
  - phase-6: verify (verification-before-completion), commit-inline (orchestrator)
  - post: audit, structural-checks (finishing-a-development-branch), pre-pr-gate, regression-check, review-prep (git-workflow-pr), create-pr (git-workflow-pr), exec-summary (completion-core)
---

# Implementation Plan — Issue 105: [SPEC-FIX] Move container to job level in upstream-release-watch workflow

- **Issue:** .issues/105/spec.md
- **Goal:** Make `.github/workflows/upstream-release-watch.yml` dispatchable and end-to-end functional: job-level `container:` (pinned uv image), zero step-level container/entrypoint keys, live dispatch accepted, run succeeds, issue-filing/state/idempotency behavior verified.
- **Architecture:** Workflow YAML restructure (job-level container) + pytest enforcement tests (test/test_workflow_jobs.py, new state-advance test) + live GitHub Actions verification. Script `scripts/release-watch.py` unchanged.
- **Files:** `.github/workflows/upstream-release-watch.yml`, `test/test_workflow_jobs.py`, `scripts/release-watch.py` (read-only), evidence artifacts under `tmp/105/artifacts/`.
- **Dispatch:** `direct (pre-implementation, commits, z5-check, HALT gates) + task-card (RED/GREEN/regression/verify/audit/checklist/PR steps)`.

## Pre-Flight Guard (Mandatory)

Check your tool list for a tool named `task`.

- Present ⇒ orchestrator — proceed.
- Absent ⇒ sub-agent — do NOT execute any instruction below. Return `BLOCKED` with `ORCHESTRATOR_ONLY_SKILL_CARD` (cards) or `ORCHESTRATOR_ONLY_PLAN` (plans) and halt.

## Blast Radius

- `.github/workflows/upstream-release-watch.yml` — restructure of the `release-watch` job (job-level container; step keys replaced). Checkout, permissions, `on:`/`concurrency:` untouched.
- `test/test_workflow_jobs.py` — existing step-level assertions rewritten to job-level assertions (SC-1/SC-2); new `run_release_watch()` state-advance test (SC-6).
- `scripts/release-watch.py` — read-only dependency (SC-6 test target; no source change).
- Live-surface: GitHub Actions workflow ID 366319756 on `michael-conrad/gitbucket`; GitHub Issues of that repo (SC-5/SC-7 observations).

## Admonishments

> **Compliance:** All SCs must pass before completion. Partial implementation is not permitted. Each item is daisy-chained — item N's commit is precondition for item N+1's RED.

> **One step at a time.** Execute exactly one step. Report progress. Wait for instruction before the next step.

> **Step status:** Report `[item N] [PASS|FAIL]` after each step. If FAIL, report blocker and halt.

> **Enforcement gate:** All SCs must pass before this plan is complete.

> **Self-Remediation Protocol:** If a step FAILs: diagnose root cause, fix the deliverable, re-verify. If the fix requires spec revision, update the spec and re-enter the plan. Escalate only after remediation failure.

## Phase Table

| Phase | Name | Concern | SCs | Depends On | Step Range | Dispatch |
|-------|------|---------|-----|-----------|------------|----------|
| 1 | workflow-restructure | Workflow YAML parseability + enforcement test | SC-1, SC-2 | — | 3-13 | direct (3, 7, 11-13) + task-card (4-6, 8-10) |
| 2 | live-dispatch-accepted | Live workflow_dispatch acceptance | SC-3 | 1 | 14-16 | direct (14, 16) + task-card (15) |
| 3 | run-conclusion-success | Run completes successfully | SC-4 | 2 | 17-19 | direct (17, 19) + task-card (18) |
| 4 | issue-filed-once | Exactly-one-issue filing on non-current state | SC-5 | 3 | 20-22 | direct (20, 22) + task-card (21) |
| 5 | state-advance-pytest | run_release_watch() state advancement | SC-6 | 1 | 23-28 | direct (28) + task-card (23-27) |
| 6 | zero-issues-idempotency | Zero-issue filing on current state | SC-7 | 4 | 29-31 | direct (31) + task-card (29-30) |
| — | Post-implementation | audit, checks, PR, completion | all | 1-6 | 32-40 | direct (33, 39) + task-card (32, 34-38, 40) |

## Exit Criteria

1. C1 — SC-1 PASS: pytest enforcement test proves job-level container with exact pinned image.
2. C2 — SC-2 PASS: pytest enforcement test proves zero step-level container/entrypoint keys.
3. C3 — SC-3 PASS: live dispatch on dev exits 0 with no HTTP 422.
4. C4 — SC-4 PASS: dispatched run conclusion is `success`.
5. C5 — SC-5 PASS: first run (state not current) files exactly one issue titled with the received tag.
6. C6 — SC-6 PASS: pytest proves `run_release_watch()` advances a temporary state file to the received tag.
7. C7 — SC-7 PASS: second dispatch (state current / dupe issue present) files zero new issues.
8. C8 — All SC verdicts verified by verification-before-completion; behavioral evidence artifacts recorded.
9. C9 — PR created (stacked, single commit per issue at squash) — human-only merge.

## Pre-Implementation Steps

- [ ] 1. **Coherence gate** (**task-card**) — verify the plan is coherent with the approved spec before any execution.
  - Confirm all SCs from spec.md map to phases per the phase table; confirm no SC is unmapped or duplicated across phases.
  - Confirm the spec's `approved-for-pr` authorization scope covers the full pipeline through PR creation.
- [ ] 2. **Baseline check** (**direct**) — record pre-work repo state.
  - Verify current branch, clean working tree, and that `.github/workflows/upstream-release-watch.yml` currently contains the invalid step-level `container:`/`entrypoint:` keys (RED precondition).
  - Verify `test/test_workflow_jobs.py` and `scripts/release-watch.py` exist and are unchanged from spec Documentation Sources state.

# Phase 1 — workflow-restructure

- **Concern:** Workflow YAML parseability + enforcement test rewrite
- **Files:** `.github/workflows/upstream-release-watch.yml`, `test/test_workflow_jobs.py`
- **SCs:** SC-1 (job-level container, exact pinned image), SC-2 (zero step-level container/entrypoint keys)
- **Dependencies:** none (first phase)
- **Entry:** baseline check (step 2) confirms step-level invalid keys present
- **Exit:** enforcement test passes against restructured workflow; commit landed
- **Code Path Coverage:** workflow YAML parse (test/test_workflow_jobs.py loading `.github/workflows/upstream-release-watch.yml`); release-watch job structure
- **Cross-Cutting SCs:** none (SC-1/SC-2 are phase-local; FR-3 run-step order and NFR-1/NFR-2 covered within these two SCs)
- **Interface Boundaries:** GitHub Actions workflow schema (`jobs.<job_id>.container` job-level key only); existing test helpers in test/test_workflow_jobs.py
- **State Transitions:** workflow moves from unparseable (HTTP 422) to parseable; test suite moves from asserting invalid shape to asserting valid shape

**Cost frame:** Running the RED/GREEN pytest cycle costs minutes of test execution. Skipping it means a structurally wrong restructure ships and every live dispatch in phases 2-6 fails HTTP 422 — discovered only after the live dispatch, minutes later, with the workflow still broken.

## Steps

- [ ] 3. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-pre-regression-*` and `rm -f tmp/105/artifacts/pipeline-red-*`
- [ ] 4. **pre-regression** (**task-card**) — dispatch `task(..., prompt: "execute phase-0 task from test-driven-development")`
  - Run existing regression test patterns (full pytest suite) before RED; record baseline artifact under `tmp/105/artifacts/`
- [ ] 5. **pre-regression-verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Verify pre-regression results are green (suite passes before any change)
- [ ] 6. **red** (**task-card**) — dispatch `task(..., prompt: "execute red task from test-driven-development")`
  - Rewrite test/test_workflow_jobs.py enforcement test: assert job-level `container:` on `release-watch` job with image exactly `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` (SC-1) AND assert every step in the job carries neither a `container:` nor an `entrypoint:` key (SC-2)
  - Run test — it must FAIL because the workflow currently declares step-level `container:`/`entrypoint:` and lacks a job-level `container:`
- [ ] 7. **red verify** (**direct**) — confirm RED recorded; test output saved to `tmp/105/artifacts/pipeline-red-phase1.yaml`
- [ ] 8. **green** (**task-card**) — dispatch `task(..., prompt: "execute green task from test-driven-development")`
  - In `.github/workflows/upstream-release-watch.yml`: add job-level `container:` with `image: ghcr.io/astral-sh/uv:python3.12-bookworm-slim` on the `release-watch` job (SC-1); remove the step-level `container:` and `entrypoint:` keys and replace them with a `run: uv run scripts/release-watch.py` step positioned after the `actions/checkout@v4` step (SC-2)
  - No other change: checkout step, permissions (`issues: write`, `contents: write`), `on:`/`concurrency:` scaffolding untouched
  - Run test — it must PASS
- [ ] 9. **post-regression** (**task-card**) — dispatch `task(..., prompt: "execute phase-4 task from test-driven-development")`
  - Run full pytest suite; assert no regression outside the touched test
- [ ] 10. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Verify SC-1 and SC-2 against spec criteria; evidence to `tmp/105/artifacts/pipeline-verify-phase1.yaml`
- [ ] 11. **commit-inline** (**direct**) — `git add .github/workflows/upstream-release-watch.yml test/test_workflow_jobs.py tmp/105/artifacts/pipeline-red-phase1.yaml tmp/105/artifacts/pipeline-verify-phase1.yaml && git commit -m "fix: move release-watch container to job level (SC-1, SC-2)"`
  - Daisy-chain precondition: this commit is Item-1b's RED precondition satisfied by the same test (SC-2 asserted in the identical RED/GREEN slice)
- [ ] 12. **report** (**direct**) — report `[phase-1] [PASS|FAIL]` per step-status format; halt on FAIL
- [ ] 13. **concern transition** (**direct**) — proceed to Phase 2 (live dispatch)

# Phase 2 — live-dispatch-accepted

- **Concern:** Live workflow_dispatch acceptance (SC-3)
- **Files:** none modified — live observation; evidence artifact only
- **SCs:** SC-3 (dispatch accepted, exit 0, no HTTP 422)
- **Dependencies:** Phase 1 commit (parseable workflow must be on `dev`)
- **Entry:** Phase 1 committed; workflow changes present on `dev`
- **Exit:** `gh workflow run upstream-release-watch.yml --ref dev` exits 0; evidence committed
- **Code Path Coverage:** GitHub Actions dispatch API surface (`gh workflow run`); workflow file ID 366319756
- **Cross-Cutting SCs:** none
- **Interface Boundaries:** `gh` CLI authenticated session; `workflow_dispatch` permission (verified)
- **State Transitions:** workflow from "committed but undispatchable" to "accepted by GitHub Actions parser"

**Cost frame:** One `gh workflow run` invocation costs about a minute of wait time. Skipping it means a 422 regression ships unnoticed and every downstream phase (3, 4, 6) operates on an unverifiable pipeline.

## Steps

- [ ] 14. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-verify-phase2-*` (then run the verify step)
- [ ] 15. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Run `gh workflow run upstream-release-watch.yml --ref dev`; observe exit code 0 and absence of HTTP 422 parse failure
  - Live evidence to `tmp/105/artifacts/pipeline-verify-phase2.yaml` (command, exit code, timestamp)
- [ ] 16. **commit-inline** (**direct**) — `git add tmp/105/artifacts/pipeline-verify-phase2.yaml && git commit -m "chore(105): SC-3 live dispatch accepted evidence"`
  - Daisy-chain precondition: this commit precedes Phase 3's run-id retrieval

# Phase 3 — run-conclusion-success

- **Concern:** Dispatched run completes with conclusion `success` (SC-4)
- **Files:** none modified — live observation; evidence artifact only
- **SCs:** SC-4
- **Dependencies:** Phase 2 (accepted dispatch with a run-id to inspect)
- **Entry:** Phase 2 evidence committed; run-id captured from Phase 2 dispatch
- **Exit:** `gh run view <run-id>` shows conclusion `success`; evidence committed
- **Code Path Coverage:** `gh run view` observation; container-internal script execution (env/shell drift check)
- **Cross-Cutting SCs:** none
- **Interface Boundaries:** `gh` CLI; GitHub Actions run state; `GITHUB_TOKEN` availability inside the job container (edge case: absent token raises `RuntimeError` — failure is surfaced, not silent)
- **State Transitions:** run state from queued/in-progress to `success`

**Cost frame:** One `gh run view` check plus a wait for completion costs minutes. Skipping it means a crash inside the container (env/shell drift from the job-level container change) ships undetected until a release is missed.

## Steps

- [ ] 17. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-verify-phase3-*` (then run the verify step)
- [ ] 18. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Wait for the Phase-2 dispatch to complete; run `gh run view <run-id>`; assert conclusion is `success`
  - If conclusion is `failure`: report FAIL, halt for self-remediation (do not proceed to Phase 4)
  - Live evidence to `tmp/105/artifacts/pipeline-verify-phase3.yaml` (run-id, conclusion, URL)
- [ ] 19. **commit-inline** (**direct**) — `git add tmp/105/artifacts/pipeline-verify-phase3.yaml && git commit -m "chore(105): SC-4 run conclusion success evidence"`
  - Daisy-chain precondition: this commit precedes Phase 4's pre-run issue-list snapshot

# Phase 4 — issue-filed-once

- **Concern:** Exactly-one-issue filing on non-current state (SC-5)
- **Files:** none modified — live observation; evidence artifact only
- **SCs:** SC-5 (exactly one new issue titled with the received tag)
- **Dependencies:** Phase 3 (a successful run is required; state file has never been advanced — precondition holds naturally on first run)
- **Entry:** Phase 3 committed; pre-run issue list captured
- **Exit:** post-run `gh issue list` diff shows exactly one new issue titled with the received tag; evidence committed
- **Code Path Coverage:** `scripts/release-watch.py` dupe-search behavior (`search_issue_exists`, `run_release_watch`) as executed live inside the container
- **Cross-Cutting SCs:** none
- **Interface Boundaries:** `gh` CLI; upstream `gitbucket/gitbucket` releases/latest API (verified reachable); GitHub Issues write permission (`issues: write`)
- **State Transitions:** GitHub Issues count +1; in-run state file advanced to received tag (edge case: repo-persisted state advance is out of scope per spec)

**Cost frame:** One `gh issue list` diff before/after costs about five minutes. Skipping it means double-filing or zero-filing goes undetected and release adoption tracking silently files wrong issue counts.

## Steps

- [ ] 20. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-verify-phase4-*` (then run the verify step)
- [ ] 21. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Precondition: persisted state file is NOT current (first run — state file never committed/advanced)
  - Capture pre-run issue list; wait for the Phase-2/3 dispatch; capture post-run issue list; assert the diff contains exactly one new issue titled with the received release tag
  - Live evidence to `tmp/105/artifacts/pipeline-verify-phase4.yaml` (pre/post counts, new issue title/URL)
- [ ] 22. **commit-inline** (**direct**) — `git add tmp/105/artifacts/pipeline-verify-phase4.yaml && git commit -m "chore(105): SC-5 exactly-one-issue evidence"`
  - Daisy-chain precondition: this commit establishes the dupe-issue precondition consumed by Phase 6 (zero-issues)

# Phase 5 — state-advance-pytest

- **Concern:** `run_release_watch()` state advancement test (SC-6)
- **Files:** `test/test_workflow_jobs.py` (or its state-advance test target as directed by the test-driven-development task)
- **SCs:** SC-6 (temporary state file advanced to received tag by `run_release_watch()`)
- **Dependencies:** Phase 1 (per spec Items order — workflow restructure lands before Item 5; `scripts/release-watch.py` itself unchanged)
- **Entry:** Phase 1 committed; full pytest suite green
- **Exit:** new pytest passes offline (mocked upstream fetch); full suite green; commit landed
- **Code Path Coverage:** `scripts/release-watch.py` `run_release_watch()` state-file write branch; mock boundaries per interface-compatibility artifact
- **Cross-Cutting SCs:** none
- **Interface Boundaries:** offline test execution via mock boundaries (upstream fetch mocked); temporary state file path (not the repo-persisted path)
- **State Transitions:** temporary state file content from stale/empty to received tag

**Cost frame:** One pytest test for `run_release_watch()` state advance costs about fifteen minutes. Skipping it means state never advances in any verified path and every subsequent run re-enters the non-current branch, relying solely on dupe search to prevent duplicate issues.

## Steps

- [ ] 23. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-red-phase5-*` (then run the RED step)
- [ ] 24. **red** (**task-card**) — dispatch `task(..., prompt: "execute red task from test-driven-development")`
  - Write pytest asserting `run_release_watch()` advances a temporary state file to the received tag, with the upstream fetch mocked offline per interface-compatibility boundaries
  - Run test — it must FAIL while the state-write branch is unexercised in the test environment
- [ ] 25. **green** (**task-card**) — dispatch `task(..., prompt: "execute green task from test-driven-development")`
  - Implement only the minimal test-side wiring that makes the RED pass (invoke `run_release_watch()` with mocked upstream fetch; assert temp state file content) — no change to `scripts/release-watch.py` logic
  - Run test — it must PASS
- [ ] 26. **post-regression** (**task-card**) — dispatch `task(..., prompt: "execute phase-4 task from test-driven-development")`
  - Run full pytest suite; assert no regression
- [ ] 27. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Evidence to `tmp/105/artifacts/pipeline-verify-phase5.yaml`
- [ ] 28. **commit-inline** (**direct**) — `git add test/test_workflow_jobs.py tmp/105/artifacts/pipeline-verify-phase5.yaml && git commit -m "test(105): SC-6 run_release_watch state-advance test"`
  - Daisy-chain precondition: this commit precedes Phase 6

# Phase 6 — zero-issues-idempotency

- **Concern:** Zero-issue filing on current state / dupe issue present (SC-7)
- **Files:** none modified — live observation; evidence artifact only
- **SCs:** SC-7 (second dispatch files zero new issues)
- **Dependencies:** Phase 4 (the dupe-keyed issue filed there establishes the precondition)
- **Entry:** Phase 4 committed; dupe issue exists on GitHub Issues
- **Exit:** second dispatch completes; post-dispatch `gh issue list` diff shows zero new issues; evidence committed
- **Code Path Coverage:** `scripts/release-watch.py` idempotency (`compute_new_tags`, `search_issue_exists`) as executed live
- **Cross-Cutting SCs:** none
- **Interface Boundaries:** `gh` CLI; GitHub Issues list (dupe search); concurrency group pends overlapping triggers (edge case: repeat dispatch may queue, not race)
- **State Transitions:** GitHub Issues count unchanged

**Cost frame:** One second dispatch plus a `gh issue list` diff costs about ten minutes. Skipping it means the idempotency guarantee is unverified and re-runs could spam duplicate [SPEC] issues on every weekly cron tick.

## Steps

- [ ] 29. **Pre-cleanup** (**direct**) — `rm -f tmp/105/artifacts/pipeline-verify-phase6-*` (then run the verify step)
- [ ] 30. **verify** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Precondition: state current / dupe issue already present (satisfied by Phase 4's filing via dupe search)
  - Second dispatch; wait for completion; assert `gh issue list` diff against pre-dispatch list shows zero new issues
  - Live evidence to `tmp/105/artifacts/pipeline-verify-phase6.yaml` (pre/post counts identical)
- [ ] 31. **commit-inline** (**direct**) — `git add tmp/105/artifacts/pipeline-verify-phase6.yaml && git commit -m "chore(105): SC-7 zero-issues idempotency evidence"`
  - Daisy-chain precondition: this commit precedes Post-Implementation

# Post-Implementation

- **Concern:** Audit, structural checks, pre-PR gate, review-prep, PR creation, completion
- **SCs:** all (SC-1 through SC-7 re-checked)
- **Dependencies:** Phases 1-6 all committed

**Cost frame:** The full post-implementation gate sequence costs one audit plus one checklist pass plus one PR creation (minutes of execution each). Skipping any gate means a FAIL verdict discovered after merge costs a full rework cycle — the death-spiral alternative to a bounded pre-merge break.

## Steps

- [ ] 32. **audit** (**task-card**) — dispatch `task(..., prompt: "execute verification-audit DiMo investigator from audit. Read \`audit/tasks/verification-audit-investigator.md\` first")`
  - Follow with validator, evaluator, arbiter in sequence; adversarial audit of all deliverables against spec 105
- [ ] 33. **z3-check** (**direct**) — run `.opencode/tools/solve check --state-path <state> --contract-path <contract>` directly for phase-order/state verification
  - Pre-cleanup first: `rm -f tmp/105/artifacts/pipeline-z3-check-*`
- [ ] 34. **structural-checks** (**task-card**) — dispatch `task(..., prompt: "execute checklist task from finishing-a-development-branch")`
  - Pre-cleanup first: `rm -f tmp/105/artifacts/pipeline-structural-checks-*`; run finishing checklist (lint, typecheck)
- [ ] 35. **pre-pr-gate** (**task-card**) — dispatch `task(..., prompt: "execute verify task from verification-before-completion")`
  - Pre-cleanup first: `rm -f tmp/105/artifacts/pipeline-pre-pr-gate-*`; read all SC verdicts; BLOCK if any FAIL (DONE_WITH_CONCERNS coerces to FAIL; EVIDENCE_TYPE_MISMATCH coerces to FAIL)
- [ ] 36. **regression-check** (**task-card**) — dispatch `task(..., prompt: "execute phase-4 task from test-driven-development")`
  - Pre-cleanup first: `rm -f tmp/105/artifacts/pipeline-regression-check-*`; final full-suite regression check before PR
- [ ] 37. **review-prep** (**task-card**) — dispatch `task(..., prompt: "execute review-prep from git-workflow-pr. Read \`git-workflow-pr/tasks/review-prep.md\` first")`
- [ ] 38. **create-pr** (**task-card**) — dispatch `task(..., prompt: "execute create task from git-workflow-pr")`
  - Stacked strategy: one branch, squashed commits, one PR; targets trunk; PR references issue 105
- [ ] 39. **HALT after PR creation** (**direct**) — do not merge; human-only merge (approval-gate-005)
- [ ] 40. **exec-summary** (**task-card**) — dispatch `task(..., prompt: "execute completion task from completion-core")`
  - Generate completion executive summary; halt once at plan end

## lifecycle_events

- 2026-09-25T00:21:42Z — plan_created — plan file `.issues/105/plan.md`, 6 phases + post-implementation, authorization_scope for_pr, pr_strategy stacked