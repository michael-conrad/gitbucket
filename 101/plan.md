---
plan_schema_version: 1
issue: 101
title: "Weekly upstream-release watch job that files specs with dupe prevention"
authorization_scope: for_pr
pr_strategy: stacked
phase_count: 4
dispatch:
  - phase-1: [test-driven-development/red, test-driven-development/green, test-driven-development/post-regression, verification-before-completion/verify]
  - phase-2: [test-driven-development/red, test-driven-development/green, test-driven-development/post-regression, verification-before-completion/verify]
  - phase-3: [test-driven-development/red, test-driven-development/green, test-driven-development/post-regression, verification-before-completion/verify]
  - phase-4: [test-driven-development/red, test-driven-development/green, test-driven-development/post-regression, verification-before-completion/verify, audit/verification-audit, finishing-a-development-branch/checklist, verification-before-completion/verify, test-driven-development/post-regression, git-workflow-pr/review-prep, git-workflow-pr/create, completion-core/completion]
---

# Implementation Plan — #101 — Weekly Upstream-Release Watch Job with Dupe Prevention

**Issue:** .issues/101/spec.md
**Remote issue:** https://github.com/michael-conrad/gitbucket/issues/101

> **Compliance:** All SCs must pass before completion. Partial implementation is not permitted. Each item is daisy-chained — item N's commit is precondition for item N+1's RED.

> **One step at a time.** Execute exactly one step. Report progress. Wait for instruction before the next step.

> **Step status:** Report `[item N] [PASS|FAIL]` after each step. If FAIL, report blocker and halt.

> **Self-Remediation Protocol:** If a step FAILs: diagnose root cause, fix the deliverable, re-verify. If the fix requires spec revision, update the spec and re-enter the plan. Escalate only after remediation failure.

**Goal:** Add a greenfield GitHub Actions weekly workflow that polls upstream gitbucket/gitbucket `releases/latest`, files exactly one spec issue per new release tag in michael-conrad/gitbucket with dupe prevention, and persists last-seen tag state as a committed repo file — no in-app code changes.

**Architecture:** A scheduled workflow (`.github/workflows/upstream-release-watch.yml`) with weekly POSIX cron + `workflow_dispatch` triggers, workflow-level `concurrency` group, and `GITHUB_TOKEN` permissions (`issues: write`, `contents: write`) runs a Python/uv container job executing `scripts/release-watch.py` (uv inline script metadata). The script polls upstream, diffs tag_name against the committed state file `.github/release-watch/last-seen-tag`, runs a pre-filing dupe search via the Search issues API, files one issue per new tag, then commits state only after confirmed filing receipt, advancing monotonically. Detection is TAG-based only — commit hashes are never treated as releases.

**Files:**
- `.github/workflows/upstream-release-watch.yml` (new)
- `scripts/release-watch.py` (new)
- `.github/release-watch/last-seen-tag` (new, committed state file)
- `test/` — enforcement and integration tests for the workflow and script

**Blast Radius:** Greenfield — no `src/main` application code is touched. Affected zones are limited to `.github/` (workflow + state file), `scripts/`, and `test/`. Failure blast radius is bounded by the dupe guard and monotonic state rule.

**Enforcement gate:** All SCs must pass before this plan is complete.

## Phase Table

| Phase | Name | Concern | SCs | Depends On | Step Range | Dispatch |
|-------|------|---------|-----|------------|------------|----------|
| 1 | Workflow skeleton (triggers + serialization) | Workflow trigger and overlap serialization | SC-1, SC-2 | — | 5-14 | direct (5-14) + task-card (5-14) |
| 2 | Tag-diff detection pipeline | Upstream poll and tag diff | SC-3, SC-4 | 1 | 15-24 | direct (15-24) + task-card (15-24) |
| 3 | Filing and dupe prevention | Ticket filing, idempotency, dupe search | SC-5, SC-6, SC-7 | 2 | 25-39 | direct (25-39) + task-card (25-39) |
| 4 | State persistence | Post-receipt state commit, monotonicity, retention | SC-8, SC-9, SC-10 | 3 | 40-54 | direct (40-54) + task-card (40-54) |

Post-implementation steps 55-62 run once after Phase 4.

## Exit Criteria

- [ ] C1. Workflow declares weekly `schedule:` cron and `workflow_dispatch` triggers (SC-1)
- [ ] C2. Workflow-level `concurrency` group serializes overlapping runs (SC-2)
- [ ] C3. Poll/diff pipeline produces the new-tag set from `tag_name` vs state file (SC-3)
- [ ] C4. Commit-hash and non-tag references yield an empty new-tag set (SC-4)
- [ ] C5. Exactly one spec issue is filed per new tag in michael-conrad/gitbucket (SC-5)
- [ ] C6. Repeat run with unchanged last-seen TAG files zero issues (SC-6)
- [ ] C7. Pre-filing dupe search skips already-filed tags (SC-7)
- [ ] C8. State advances only after confirmed filing receipt; empty state bootstraps by filing current latest tag (SC-8)
- [ ] C9. State never regresses to an older tag (SC-9)
- [ ] C10. State persists across workflow runs via the committed state file (SC-10)

## Pre-Flight Guard (Mandatory)

Check your tool list for a tool named `task`.

- Present ⇒ orchestrator — proceed.
- Absent ⇒ sub-agent — do NOT execute any instruction below. Return `BLOCKED` with `ORCHESTRATOR_ONLY_SKILL_CARD` (cards) or `ORCHESTRATOR_ONLY_PLAN` (plans) and halt.

## Pre-Implementation Steps

- [ ] 1. **Coherence gate (**direct**).** Verify the spec's SC list, phase mappings, and dependency DAG are coherent — 10 SCs mapped across 4 linear phases, all behavioral evidence. **→ all SCs**
  - Read the verification ledger at `.issues/101/artifacts/plan-input-verification.md` instead of re-probing sources.
  - If the ledger disagrees with the spec, return BLOCKED with `PLAN_LEDGER_MISMATCH`.
- [ ] 2. **Baseline check (**direct**).** Confirm the repository is on a feature branch with zero pending changes and that `.github/workflows/`, `scripts/`, `.github/release-watch/`, and `test/` state matches the greenfield assumption. **→ all SCs**
  - If the workflow or script files already exist, return BLOCKED with `GREENFIELD_STATE_VIOLATED`.
- [ ] 3. **Pre-regression (**task-card**).** Run regression test patterns before RED phase. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-pre-regression-*`.
- [ ] 4. **Pre-regression verify (**task-card**).** Verify pre-regression results before entering Phase 1. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-pre-regression-verify-*`.

## Phase 1 — Workflow skeleton (triggers + serialization)

**Concern:** Define the workflow's execution triggers and overlap serialization before any pipeline logic exists.

**Files:**
- `.github/workflows/upstream-release-watch.yml` (new)
- `test/` — workflow-definition enforcement tests

**Code Path Coverage:** Workflow definition parse path (YAML load of the workflow file); trigger declaration path (`on.schedule`, `on.workflow_dispatch`); concurrency declaration path (`concurrency` key). All exercised from the enforcement tests, not from runtime.

**Cross-Cutting SCs:** None — SC-1 and SC-2 are scoped entirely to this phase.

**Interface Boundaries:** The workflow file is the boundary consumed by the GitHub Actions platform; the tests consume the file's YAML structure. No script interface exists yet.

**State Transitions:** None — no state file is created in this phase.

**Entry Conditions:** Pre-implementation steps 1-4 complete; greenfield state confirmed.

**Exit Conditions:** Workflow file exists with weekly cron + `workflow_dispatch` triggers and a `concurrency` group; both SC enforcement tests pass.

**Cost frame:** Verifying the trigger and concurrency declarations with workflow-definition tests costs minutes. Skipping means a mis-declared schedule never fires (or fires too often) and overlapping runs interleave dupe search and state commits — defects discovered only in repo history weeks later.

**Step-by-step:**

- [ ] 5. **RED (**task-card**).** Write a failing enforcement test asserting the workflow declares a weekly `schedule:` POSIX cron entry and a `workflow_dispatch` trigger — the test fails because the workflow file does not exist yet. **→ SC-1**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-red-*`.
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 6. **GREEN (**task-card**).** Create `.github/workflows/upstream-release-watch.yml` with `on.schedule` (weekly POSIX cron) plus `on.workflow_dispatch` — the minimum change that makes the SC-1 test pass. **→ SC-1**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 7. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-1**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-post-regression-*`.
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 8. **Verify (**task-card**).** Verify the test parses the workflow definition and asserts the weekly schedule and manual trigger exist. **→ SC-1**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-verify-*`.
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 9. **Commit (**direct**).** Stage and commit the workflow trigger and its test as one atomic slice. **→ SC-1**
  - `git add .github/workflows/upstream-release-watch.yml <test files> && git commit -m "workflow trigger + test"` — no co-author trailers at implementation time.
- [ ] 10. **RED (**task-card**).** Write a failing enforcement test asserting a workflow-level `concurrency` group exists — fails because no concurrency key is declared. **→ SC-2**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 11. **GREEN (**task-card**).** Add `concurrency` with the `upstream-release-watch` group (default `cancel-in-progress: false`) — the minimum change that makes the SC-2 test pass. **→ SC-2**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 12. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-2**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 13. **Verify (**task-card**).** Verify the test asserts the concurrency group; platform semantics guarantee single-run execution per group. **→ SC-2**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 14. **Commit (**direct**).** Stage and commit the concurrency guard and its test. **→ SC-2**
  - `git add .github/workflows/upstream-release-watch.yml <test files> && git commit -m "concurrency guard + test"`

#### Phase 1 Completion Block (VbC)

- [ ] 14a. **VbC (**task-card**).** Verify both SC enforcement tests pass against the committed workflow; confirm the workflow file contains schedule, workflow_dispatch, and concurrency keys. **→ SC-1, SC-2**

**Concern transition:** Leaving workflow trigger and serialization definition → entering the tag-diff detection pipeline. Phase 2 depends on Phase 1's workflow file, which hosts the job that runs the detection script.

## Phase 2 — Tag-diff detection pipeline

**Concern:** Poll the upstream latest-release tag and diff it against persisted state to produce the new-tag set, with tag-shaped detection only.

**Files:**
- `scripts/release-watch.py` (new)
- `test/` — mocked-upstream integration tests

**Code Path Coverage:** Upstream poll path (`GET /repos/gitbucket/gitbucket/releases/latest` → `tag_name` extraction); state-file read path (`.github/release-watch/last-seen-tag` from the checkout); diff path (producing the new-tag set); exclusion path (non-tag references yield an empty new-tag set). Mocked-upstream integration tests exercise all four.

**Cross-Cutting SCs:** SC-3 and SC-4 are both satisfied inside the detection pipeline; SC-4 constrains the same diff path SC-3 exercises (its exclusion branch).

**Interface Boundaries:** The script's public entry (`uv run scripts/release-watch.py`) is the boundary the workflow job will invoke in later phases; tests call the script's detection function directly with injected upstream responses.

**State Transitions:** Reads the last-seen TAG; produces the new-tag set in memory. No state writes in this phase (writes are Phase 4).

**Entry Conditions:** Phase 1 complete — workflow skeleton committed; Phase 1 VbC passed.

**Exit Conditions:** Detection pipeline produces the new-tag set from a mocked upstream; commit-hash and non-tag references yield an empty new-tag set; both SC tests pass.

**Cost frame:** Running the mocked-upstream diff and exclusion tests costs minutes. Skipping means stale-tag mishandling ships silently and raw commit hashes slip through as "releases" — a direct violation of the binding constraint, discovered weeks later as spurious tickets.

**Step-by-step:**

- [ ] 15. **RED (**task-card**).** Write a failing enforcement test asserting the poll/diff pipeline produces a new-tag set — fails because the script does not exist yet. **→ SC-3**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-red-*`.
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 16. **GREEN (**task-card**).** Create `scripts/release-watch.py` with inline uv script metadata; fetch `releases/latest` → `tag_name` and diff it against the state file read from the checkout — the minimum change that makes the SC-3 test pass. **→ SC-3**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 17. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-3**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 18. **Verify (**task-card**).** Verify the mocked-upstream integration test — a tag yields the new-release set. **→ SC-3**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 19. **Commit (**direct**).** Stage and commit the client, diff logic, and test. **→ SC-3**
  - `git add scripts/release-watch.py <test files> && git commit -m "client + diff logic + test"`
- [ ] 20. **RED (**task-card**).** Write a failing enforcement test asserting a commit-hash reference is never treated as a release. **→ SC-4**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 21. **GREEN (**task-card**).** Make the detection path accept only tag-shaped values from `tag_name`; non-tag references yield an empty new-tag set — the minimum change that makes the SC-4 test pass. **→ SC-4**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 22. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-4**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 23. **Verify (**task-card**).** Verify the integration test — a commit-hash reference yields an empty new-tag set. **→ SC-4**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 24. **Commit (**direct**).** Stage and commit the exclusion guard and its test. **→ SC-4**
  - `git add scripts/release-watch.py <test files> && git commit -m "exclusion guard + test"`

#### Phase 2 Completion Block (VbC)

- [ ] 24a. **VbC (**task-card**).** Verify both SC-3 and SC-4 tests pass against the committed script; confirm the detection path reads the state file and rejects non-tag values. **→ SC-3, SC-4**

**Concern transition:** Leaving tag-diff detection → entering filing and dupe prevention. Phase 3 consumes the new-tag set produced by Phase 2's pipeline.

## Phase 3 — Filing and dupe prevention

**Concern:** File exactly one spec issue per new tag in michael-conrad/gitbucket, idempotently, guarded by a pre-filing dupe search.

**Files:**
- `scripts/release-watch.py` (extend)
- `test/` — filing, idempotency, and dupe-guard integration/behavioral tests

**Code Path Coverage:** Filing path (GitHub API issue creation authenticated as `GITHUB_TOKEN`, `issues: write`); idempotency path (unchanged last-seen TAG → zero filings); dupe-search path (Search issues API keyed on the tag string in the issue title, open or closed → skip when present). Tests exercise each path against mocked API responses.

**Cross-Cutting SCs:** SC-5, SC-6, and SC-7 share the filing pipeline; SC-6 and SC-7 are the two guards (state-based idempotency and search-based dupe skip) that make SC-5's "exactly one" guarantee hold across runs.

**Interface Boundaries:** GitHub REST API surface (`POST /repos/michael-conrad/gitbucket/issues`, `GET /search/issues`) is the external boundary; the script's internal boundary is the new-tag set consumed from Phase 2's diff path.

**State Transitions:** None — this phase reads state and files issues but does not advance state (advancement is Phase 4, gated on filing receipt).

**Entry Conditions:** Phase 2 complete — detection pipeline produces the new-tag set; Phase 2 VbC passed.

**Exit Conditions:** One issue filed per new tag; repeat run files zero issues; already-filed tags are skipped; all three SC tests pass.

**Cost frame:** Running the one-issue-per-tag, idempotency, and dupe-search tests costs minutes. Skipping means missing or multiplied filings surface only via manual release checks, and duplicate issues compound with every re-detection — cleanup costs grow with each skipped run.

**Step-by-step:**

- [ ] 25. **RED (**task-card**).** Write a failing enforcement test asserting one issue per new tag is filed in michael-conrad/gitbucket. **→ SC-5**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-red-*`.
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 26. **GREEN (**task-card**).** Add the filing step in the script creating one issue per new tag via the GitHub API (`GITHUB_TOKEN`, `issues: write`) — the minimum change that makes the SC-5 test pass. **→ SC-5**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 27. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-5**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 28. **Verify (**task-card**).** Verify the integration test observing one filed issue per tag. **→ SC-5**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 29. **Commit (**direct**).** Stage and commit the filing logic and test. **→ SC-5**
  - `git add scripts/release-watch.py <test files> && git commit -m "filing logic + test"`
- [ ] 30. **RED (**task-card**).** Write a failing enforcement test asserting a repeat run with unchanged last-seen TAG files zero issues. **→ SC-6**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 31. **GREEN (**task-card**).** Implement idempotency semantics — unchanged last-seen TAG means zero filings — the minimum change that makes the SC-6 test pass. **→ SC-6**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 32. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-6**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 33. **Verify (**task-card**).** Verify the behavioral test — two consecutive runs, zero filings on the second. **→ SC-6**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 34. **Commit (**direct**).** Stage and commit the idempotency logic and test. **→ SC-6**
  - `git add scripts/release-watch.py <test files> && git commit -m "idempotency logic + test"`
- [ ] 35. **RED (**task-card**).** Write a failing enforcement test asserting an already-filed tag is skipped. **→ SC-7**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 36. **GREEN (**task-card**).** Add the pre-file Search issues API query keyed on the tag (tag string in issue title, open or closed) plus skip-when-present logic — the minimum change that makes the SC-7 test pass. **→ SC-7**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 37. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-7**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 38. **Verify (**task-card**).** Verify the behavioral test — state containing an already-filed tag produces no new issue for it. **→ SC-7**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 39. **Commit (**direct**).** Stage and commit the guard logic and test. **→ SC-7**
  - `git add scripts/release-watch.py <test files> && git commit -m "guard logic + test"`

#### Phase 3 Completion Block (VbC)

- [ ] 39a. **VbC (**task-card**).** Verify SC-5, SC-6, SC-7 tests pass against the committed script; confirm filing is authenticated, idempotent, and dupe-guarded. **→ SC-5, SC-6, SC-7**

**Concern transition:** Leaving filing and dupe prevention → entering state persistence. Phase 4 advances the committed state file only after Phase 3's confirmed filing receipt.

## Phase 4 — State persistence

**Concern:** Commit last-seen TAG state only after confirmed filing receipt, advance it monotonically, and retain it across workflow runs.

**Files:**
- `scripts/release-watch.py` (extend)
- `.github/release-watch/last-seen-tag` (new, committed state file)
- `.github/workflows/upstream-release-watch.yml` (extend — `contents: write` usage for the state commit)
- `test/` — state persistence, monotonicity, and retention integration/behavioral tests

**Code Path Coverage:** State-write path (write and commit `.github/release-watch/last-seen-tag`, pushed with `contents: write`); failure path (failed filing leaves state unchanged); bootstrap path (absent or empty state file → file the current latest tag, then advance state); monotonic path (older tag never regresses persisted state); retention path (fresh run reads committed state; no-release run leaves state unchanged).

**Cross-Cutting SCs:** SC-8, SC-9, and SC-10 share the state store; SC-9 constrains SC-8's write path (monotonic advance) and SC-10 constrains its read path (retention across runs).

**Interface Boundaries:** Git commit/push of the state file is the persistence boundary; the workflow's `contents: write` permission is the authorization boundary for that commit. The state-file read boundary is the script's checkout-local read established in Phase 2.

**State Transitions:** `absent/empty → filed current latest tag → state advanced to it` (bootstrap); `filing success → state = filed tag` (post-receipt); `filing failure → state unchanged` (no-advance); `older tag offered → state unchanged` (monotonic); `no new release → state unchanged` (retention).

**Entry Conditions:** Phase 3 complete — filing pipeline with dupe guard committed; Phase 3 VbC passed.

**Exit Conditions:** State advances only after confirmed filing receipt, never regresses, and persists across runs; all three SC tests pass.

**Cost frame:** Verifying post-receipt persistence, monotonic advance, and cross-run retention costs minutes. Skipping means state commits on failed filings, a regression to an older tag re-files the entire back-range, or lost state re-files whole release histories after each run — exponentially costly cleanups.

**Step-by-step:**

- [ ] 40. **RED (**task-card**).** Write a failing enforcement test asserting state does not advance on filing failure. **→ SC-8**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-red-*`.
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 41. **GREEN (**task-card**).** Implement the state file written and committed (pushed with `contents: write`) only after a confirmed filing receipt — the minimum change that makes the SC-8 test pass. **→ SC-8**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 42. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-8**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 43. **Verify (**task-card**).** Verify the integration test — failed filing leaves state unchanged; success commits the filed tag; absent/empty state file bootstraps by filing the current latest tag then advancing. **→ SC-8**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 44. **Commit (**direct**).** Stage and commit the state store and test. **→ SC-8**
  - `git add scripts/release-watch.py .github/release-watch/last-seen-tag <test files> && git commit -m "state store + test"`
- [ ] 45. **RED (**task-card**).** Write a failing enforcement test asserting state cannot regress to an older tag. **→ SC-9**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 46. **GREEN (**task-card**).** Add the monotonic advance rule in the state update logic — the minimum change that makes the SC-9 test pass. **→ SC-9**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 47. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-9**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 48. **Verify (**task-card**).** Verify the behavioral test — an older tag after a newer one does not regress persisted state. **→ SC-9**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 49. **Commit (**direct**).** Stage and commit the monotonic rule and test. **→ SC-9**
  - `git add scripts/release-watch.py <test files> && git commit -m "monotonic rule + test"`
- [ ] 50. **RED (**task-card**).** Write a failing enforcement test asserting state survives a fresh run. **→ SC-10**
  - Dispatch: `task(..., prompt: "execute red task from test-driven-development")`
- [ ] 51. **GREEN (**task-card**).** Implement durable persistence via the committed state file; each run reads the committed state from its checkout — the minimum change that makes the SC-10 test pass. **→ SC-10**
  - Dispatch: `task(..., prompt: "execute green task from test-driven-development")`
- [ ] 52. **Post-regression (**task-card**).** Run regression test patterns after GREEN. **→ SC-10**
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 53. **Verify (**task-card**).** Verify the integration test — a fresh run with a committed state file present reads it correctly; a no-release run leaves state unchanged. **→ SC-10**
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 54. **Commit (**direct**).** Stage and commit the durable store and test. **→ SC-10**
  - `git add scripts/release-watch.py <test files> && git commit -m "durable store + test"`

#### Phase 4 Completion Block (VbC)

- [ ] 54a. **VbC (**task-card**).** Verify SC-8, SC-9, SC-10 tests pass against the committed script and state file; confirm bootstrap, monotonicity, and retention semantics. **→ SC-8, SC-9, SC-10**

## Post-Implementation Steps

**Cost frame:** Running the adversarial audit, Z3 check, structural checks, pre-PR gate, and final regression check costs minutes. Skipping means a spec-fidelity defect, pipeline-state contradiction, lint/typecheck failure, or an unverified SC ships in the PR — each discovered in review or CI at 10×-1000× the gate cost.

- [ ] 55. **Audit (**task-card**).** Adversarial audit of the deliverable. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-audit-*`.
  - Dispatch: `task(..., prompt: "execute verification-audit DiMo investigator from audit. Read `audit/tasks/verification-audit-investigator.md` first")` — followed by validator, evaluator, arbiter in sequence.
- [ ] 56. **Z3 check (**direct**).** Run the Z3 constraint solver verification on the pipeline state. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-z3-check-*`.
  - `.opencode/tools/solve check --state-path <state file> --contract-path <contract file>`
- [ ] 57. **Structural checks (**task-card**).** Run the finishing checklist (lint, typecheck, etc.). **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-structural-checks-*`.
  - Dispatch: `task(..., prompt: "execute checklist task from finishing-a-development-branch")`
- [ ] 58. **Pre-PR gate (**task-card**).** Read all SC verdicts; BLOCK if any FAIL. DONE_WITH_CONCERNS coerces to FAIL. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-pre-pr-gate-*`.
  - Dispatch: `task(..., prompt: "execute verify task from verification-before-completion")`
- [ ] 59. **Regression check (**task-card**).** Final regression check before PR. **→ all SCs**
  - Pre-cleanup: remove `tmp/101/artifacts/pipeline-regression-check-*`.
  - Dispatch: `task(..., prompt: "execute phase-4 task from test-driven-development")`
- [ ] 60. **Review-prep (**task-card**).** Prepare PR review context. **→ all SCs**
  - Dispatch: `task(..., prompt: "execute review-prep from git-workflow-pr. Read `git-workflow-pr/tasks/review-prep.md` first")`
- [ ] 61. **Create PR (**task-card**).** Create the pull request (stacked strategy — one branch, squashed commits, one PR). **→ all SCs**
  - Dispatch: `task(..., prompt: "execute create task from git-workflow-pr")`
- [ ] 62. **Executive summary (**task-card**).** Generate the completion executive summary. **→ all SCs**
  - Dispatch: `task(..., prompt: "execute completion task from completion-core")`

---

<!-- SPDX-FileCopyrightText: 2026 Michael Conrad -->
<!-- SPDX-License-Identifier: MIT -->
<!-- Provenance: AI-generated -->

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)

## Lifecycle Events

- 20260924135149 — plan_created — plan file: .issues/101/plan.md — phase_count: 4
