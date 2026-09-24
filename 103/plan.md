---
plan_schema_version: "1.0"
issue: 103
title: "Wire GitHub Actions job into upstream-release-watch workflow"
authorization_scope: for_pr
pr_strategy: stacked
phase_count: 2
dispatch:
  - phase-1: pre-regression, pre-regression-verify, red (test-driven-development), green (test-driven-development), post-regression (test-driven-development), verify (verification-before-completion), commit-inline (orchestrator)
  - phase-2: red (test-driven-development), green (test-driven-development), verify (verification-before-completion), commit n/a (live evidence)
  - post: audit, z3-check (orchestrator), structural-checks (finishing-a-development-branch), pre-pr-gate (verification-before-completion), regression-check (test-driven-development), review-prep (git-workflow-pr), create-pr (git-workflow-pr), exec-summary (completion-core)
---

# Implementation Plan — #103 — Wire GitHub Actions Job into upstream-release-watch Workflow

- **Issue:** .issues/103/spec.md
- **Repo:** michael-conrad/gitbucket (github.com)

## Pre-Flight Guard (Mandatory)

Check your tool list for a tool named `task`.

- Present ⇒ orchestrator — proceed.
- Absent ⇒ sub-agent — do NOT execute any instruction below. Return `BLOCKED` with `ORCHESTRATOR_ONLY_SKILL_CARD` (cards) or `ORCHESTRATOR_ONLY_PLAN` (plans) and halt.

## Goal

Add a `jobs:` section with one job to `.github/workflows/upstream-release-watch.yml` that checks out the repo, declares `issues:write`/`contents:write` permissions, and invokes `scripts/release-watch.py` in a Python+uv container — verified first by pytest enforcement tests, then by live `workflow_dispatch` runs on `dev`.

## Architecture

One job (`release-watch`) appended to the existing workflow whose `on:`/`concurrency:` scaffolding is preserved untouched (NFR-2). Structural SCs (SC-1..SC-4) verified by pytest enforcement tests in `test/` parsing the workflow YAML; behavioral SCs (SC-5..SC-9) verified by live GitHub API observation on `dev` — dispatch acceptance, run success, single issue filing, state-file commit advance, and zero-dupe repeat dispatch. Phase 1 delivers the wiring (commits); phase 2 verifies it live (commit n/a — evidence from live runs).

## Files

- `.github/workflows/upstream-release-watch.yml` — extend with `jobs:` section
- `test/` — new workflow-job enforcement test file

## Phase Table

| Phase | Name | Concern | SCs | Depends On | Step Range | Dispatch |
|-------|------|---------|-----|------------|------------|----------|
| 1 | workflow-structural-wiring | Add `jobs:` section with one job (checkout, permissions, container script invocation) | SC-1, SC-2, SC-3, SC-4 | — | 4-24 | direct (7, 12, 17, 22) + task-card (rest) |
| 2 | live-dispatch-verification | Merge to dev, verify via live workflow_dispatch (acceptance, success, filing, state, idempotence) | SC-5, SC-6, SC-7, SC-8, SC-9 | 1 | 25-32 | task-card |

## Admonishments

> **Compliance:** All SCs must pass before completion. Partial implementation is not permitted. Each item is daisy-chained — item N's commit is precondition for item N+1's RED.

> **One step at a time.** Execute exactly one step. Report progress. Wait for instruction before the next step.

> **Step status:** Report `[item N] [PASS|FAIL]` after each step. If FAIL, report blocker and halt.

> **Self-Remediation Protocol:** If a step FAILs: diagnose root cause, fix the deliverable, re-verify. If the fix requires spec revision, update the spec and re-enter the plan. Escalate only after remediation failure.

> **Enforcement gate:** All SCs must pass before this plan is complete.

## Exit Criteria

- [ ] C1. Workflow file contains a `jobs:` section with one job `release-watch`; existing `on:`/`concurrency:` scaffolding unchanged (SC-1)
- [ ] C2. Job has an `actions/checkout@*` step preceding the script-invocation step (SC-2)
- [ ] C3. Job declares `permissions:` exactly `issues: write` and `contents: write` (SC-3)
- [ ] C4. Job invokes `scripts/release-watch.py` in a container step with image `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` (SC-4)
- [ ] C5. `workflow_dispatch` on `dev` accepted — exit 0, no HTTP 422 (SC-5)
- [ ] C6. Dispatched run completes with `status: completed`, `conclusion: success` (SC-6)
- [ ] C7. First successful run files exactly one issue in michael-conrad/gitbucket for the latest upstream tag (SC-7)
- [ ] C8. `.github/release-watch/last-seen-tag` commit advanced to the received tag (SC-8)
- [ ] C9. Repeat dispatch with state current files zero new issues (SC-9)

---

## Pre-Implementation (once per plan)

- [ ] 1. **Coherence gate + baseline check (**direct**).** Verify the spec `.issues/103/spec.md` is approved (label `approved-for-for_pr` present), structure artifact exists at `.issues/103/artifacts/structure.yaml`, `scripts/release-watch.py` and its 16 enforcement tests are present on `dev`, and `.github/workflows/upstream-release-watch.yml` currently has no `jobs:` section. If any check fails: BLOCKED with reason — do not proceed.
- [ ] 2. **Pre-regression (**task-card**).** Execute the pre-regression step — run existing regression test patterns before RED phase.
- [ ] 3. **Pre-regression verify (**task-card**).** Execute the pre-regression verify step — verify pre-regression results before entering Phase 1.

---

## Phase 1 — Workflow Structural Wiring

**Concern:** Add the `jobs:` section with one job (`release-watch`) including checkout, permissions, and container script-invocation steps; covered state transition S0 → S1 (workflow-structurally-complete).

**Files:**
- `.github/workflows/upstream-release-watch.yml`
- `test/` (new enforcement test file)

**SCs:** SC-1, SC-2, SC-3, SC-4

**Dependencies:** None (entry phase)

**Entry conditions:**
- Pre-implementation steps 1-3 complete (coherence gate passed, pre-regression clean)
- Feature branch created per git-workflow pre-work (`for_pr` scope authorized)

**Exit conditions:**
- All four enforcement tests pass against the workflow file
- `on:`/`concurrency:` scaffolding unchanged (diff shows only `jobs:` addition)
- Each item's test + implementation committed as one atomic slice

**Code path coverage:** workflow YAML parse path (`jobs:`, `jobs.release-watch.steps`, `jobs.release-watch.permissions`), test parse path (`test/test_release_watch_workflow.py` or equivalent under `test/`).

**Cross-cutting SCs:** SC-1 also carries NFR-2 (preserve `on:`/`concurrency:` unmodified) — its verify step asserts the diff shows only the `jobs:` addition.

**Interface boundaries:** job name `release-watch`; container image `ghcr.io/astral-sh/uv:python3.12-bookworm-slim`; script entrypoint `scripts/release-watch.py`; token permissions exactly `issues: write`, `contents: write`.

**State transitions:** S0 (no jobs section) → S1 (workflow-structurally-complete) at phase exit.

**Cost frame:** Structural enforcement tests cost minutes of execution time each — workflow-structure defects surface at CI time, before any live run. Skipping costs one full discovery latency cycle — the missing `jobs:` wiring is invisible until a live dispatch 422, exactly the defect state this spec removes.

- [ ] 4. **RED for SC-1 (**task-card**).** Write failing pytest enforcement test under `test/` that parses the workflow YAML and asserts a `jobs:` key with at least one entry exists — it fails because the current file has no `jobs:` section. **→ SC-1**
- [ ] 5. **GREEN for SC-1 (**task-card**).** Add the `jobs:` section with one job `release-watch` to the workflow file — the minimum change that makes the RED test pass; `on:`/`concurrency:` untouched. **→ SC-1**
- [ ] 6. **Verify SC-1 (**task-card**).** Execute the verify task from verification-before-completion — enforcement test PASS; diff shows only the `jobs:` addition (scaffolding unchanged, NFR-2). **→ SC-1**
- [ ] 7. **Commit SC-1 (**direct**).** Orchestrator stages `.github/workflows/upstream-release-watch.yml` and the enforcement test file and commits both as one atomic slice. **→ SC-1**
- [ ] 8. **Post-regression (**task-card**).** Execute the phase-4 task from test-driven-development — regression test patterns after GREEN for SC-1, before SC-2's RED.

Items SC-2, SC-3, SC-4 follow the same daisy-chained cycle against the same files; item N's commit is precondition for item N+1's RED:

- [ ] 9. **RED for SC-2 (**task-card**).** Write failing test asserting the job contains an `actions/checkout@*` step preceding any script-invocation step — fails against current step list. **→ SC-2**
- [ ] 10. **GREEN for SC-2 (**task-card**).** Add `uses: actions/checkout@v4` as the first step of the job. **→ SC-2**
- [ ] 11. **Verify SC-2 (**task-card**).** Checkout step precedes all other steps in the parsed step list. **→ SC-2**
- [ ] 12. **Commit SC-2 (**direct**).** Stage and commit workflow file + test file. **→ SC-2**
- [ ] 13. **Post-regression (**task-card**).** Regression test patterns after GREEN for SC-2. **→ SC-2**

- [ ] 14. **RED for SC-3 (**task-card**).** Write failing test asserting the job declares `permissions:` with exactly `issues: write` and `contents: write` — fails (absent). **→ SC-3**
- [ ] 15. **GREEN for SC-3 (**task-card**).** Add `permissions` with `issues: write` and `contents: write` to the job — nothing broader. **→ SC-3**
- [ ] 16. **Verify SC-3 (**task-card**).** Enforcement test PASS; no broader permission scopes present. **→ SC-3**
- [ ] 17. **Commit SC-3 (**direct**).** Stage and commit workflow file + test file. **→ SC-3**
- [ ] 18. **Post-regression (**task-card**).** Regression test patterns after GREEN for SC-3. **→ SC-3**

- [ ] 19. **RED for SC-4 (**task-card**).** Write failing test asserting the job has a container step whose image is exactly `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` and that invokes `scripts/release-watch.py` — fails (absent). **→ SC-4**
- [ ] 20. **GREEN for SC-4 (**task-card**).** Add the container step with the pinned uv image whose entrypoint invokes `scripts/release-watch.py`. **→ SC-4**
- [ ] 21. **Verify SC-4 (**task-card**).** Enforcement test PASS including exact image string assertion (NFR-1). **→ SC-4**
- [ ] 22. **Commit SC-4 (**direct**).** Stage and commit workflow file + test file. **→ SC-4**
- [ ] 23. **Post-regression (**task-card**).** Regression test patterns after GREEN for SC-4 — final structural phase gate.

#### Phase 1 Completion Block (VbC)

- [ ] 24. **VbC (**task-card**).** Execute the verify task from verification-before-completion — verify SC-1..SC-4 verdicts are PASS from recorded evidence; any DONE_WITH_CONCERNS coerces to FAIL; any EVIDENCE_TYPE_MISMATCH is a hard FAIL. BLOCK on any FAIL — do not proceed to Phase 2.

**Concern transition:** Leaving workflow structural wiring → entering live dispatch verification. Phase 2 depends on Phase 1's merged workflow structure on `dev`.

---

## Phase 2 — Live Dispatch Verification

**Concern:** Verify the wired workflow via live `workflow_dispatch` on `dev`: dispatch accepted (no 422), run completes success, first run files exactly one issue, state-file commit advances, repeat dispatch files zero new issues. Covered state transitions S1 → S2 → S3 → S4.

**Files:** none modified — live verification; all evidence recorded from live GitHub API runs.

**SCs:** SC-5, SC-6, SC-7, SC-8, SC-9

**Dependencies:** Phase 1 (dispatchable workflow merged to `dev`)

**Entry conditions:**
- Phase 1 VbC passed (SC-1..SC-4 all PASS)
- Phase 1 branch merged to `dev` so the workflow with `jobs:` is dispatchable (live runs execute on `dev`)

**Exit conditions:**
- Dispatch accepted without HTTP 422
- Run observed completed with conclusion success
- Exactly one new issue filed for the latest upstream tag
- State-file commit history shows `.github/release-watch/last-seen-tag` advanced
- Repeat dispatch files zero new issues

**Code path coverage:** live GitHub Actions run path (workflow_dispatch API, job execution, container pull), script pipeline path (poll → dupe search → file issue → state commit), issue-list observation path.

**Cross-cutting SCs:** none — each live SC is a distinct observation point on the same run sequence.

**Interface boundaries:** `gh workflow run upstream-release-watch.yml --ref dev`, `gh run view <run-id>`, `gh issue list` in michael-conrad/gitbucket, state file `.github/release-watch/last-seen-tag` git history.

**State transitions:** S1 (structurally complete) → S2 (dispatch-accepted) → S3 (run-success + filed + state-advanced) → S4 (idempotent-repeat-verified).

**Cost frame:** Live dispatch verification costs bounded `gh` calls (dispatch, `run view`, `issue list`) — a red run, duplicate filing, or non-advancing state file is caught immediately. Skipping costs the entire spec's purpose — a structurally plausible but undeliverable workflow, a false PASS worse than the original defect. Commit is n/a for these items — evidence is recorded from live runs per the spec Items.

- [ ] 25. **RED for SC-5 (live probe) (**task-card**).** Live probe on `dev` — if the phase-1 wiring is not yet merged, dispatch returns HTTP 422 (the documented RED state); confirm the 422 signature exists before merge. **→ SC-5**
- [ ] 26. **GREEN for SC-5 (**task-card**).** Execute the green task from test-driven-development for live context — after the phase-1 workflow file is merged to `dev`, dispatch the workflow live via `gh workflow run ... --ref dev`; exit 0, no 422. **→ SC-5**
- [ ] 27. **Verify SC-5 (live) (**task-card**).** Execute the verify task from verification-before-completion — record live dispatch evidence (exit code, absence of 422). Structural YAML evidence is NOT accepted for this SC. **→ SC-5**
- [ ] 28. **Observe SC-6 (**task-card**).** Poll `gh run view <run-id>` until `status: completed`, record conclusion — RED state was no dispatchable run at all; GREEN is `conclusion: success`. **→ SC-6**
- [ ] 29. **Observe SC-7 (**task-card**).** Record `gh issue list` in michael-conrad/gitbucket — exactly one new issue referencing the latest upstream tag from this run. **→ SC-7**
- [ ] 30. **Observe SC-8 (**task-card**).** Record git history of `.github/release-watch/last-seen-tag` — post-filing commit advanced the state file to the received tag. **→ SC-8**
- [ ] 31. **Observe SC-9 (**task-card**).** Execute the verify task from verification-before-completion for the repeat dispatch — second `gh workflow run` dispatch, `gh run view` completed, `gh issue list` shows zero new issues. **→ SC-9**

#### Phase 2 Completion Block (VbC)

- [ ] 32. **VbC (**task-card**).** Execute the verify task from verification-before-completion — verify SC-5..SC-9 verdicts are PASS from live-run evidence artifacts; DONE_WITH_CONCERNS coerces to FAIL; EVIDENCE_TYPE_MISMATCH (structural evidence for a live SC) is a hard FAIL. BLOCK on any FAIL — do not proceed to Post-Implementation.

**Concern transition:** Leaving live dispatch verification → entering post-implementation (audit, structural checks, pre-PR gate, review prep, PR creation). Post-implementation depends on all SC verdicts PASS.

---

## Post-Implementation (once per plan)

- [ ] 33. **Audit (**task-card**).** Execute the verification-audit DiMo investigator from audit (read the audit verification-audit-investigator task first), followed by validator, evaluator, arbiter in sequence — adversarial audit of the deliverable.
- [ ] 34. **Z3 check (**direct**).** Run `.opencode/tools/solve check` directly against the phase state contract.
- [ ] 35. **Structural checks (**task-card**).** Execute the checklist task from finishing-a-development-branch — lint, typecheck, and finishing checklist.
- [ ] 36. **Pre-PR gate (**task-card**).** Execute the verify task from verification-before-completion — reads all SC verdicts, BLOCKs if any FAIL.
- [ ] 37. **Regression check (**task-card**).** Execute the phase-4 task from test-driven-development — final regression check before PR.
- [ ] 38. **Review prep (**task-card**).** Execute the review-prep task from git-workflow-pr (read the git-workflow-pr review-prep task first).
- [ ] 39. **Create PR (**task-card**).** Execute the create task from git-workflow-pr — stacked PR targeting the trunk; halt after creation (merge is human-only).
- [ ] 40. **Executive summary (**task-card**).** Execute the completion task from completion-core — emit `plan_created` lifecycle event with `plan_file` and `phase_count`.

---

**Cost frames** (dark-prose-007; cost = defect-discovery-latency, not tool calls):

- **Phase 1:** Structural enforcement tests cost minutes of execution time each — workflow-structure defects surface at CI time, before any live run. Skipping costs one full discovery latency cycle — the missing `jobs:` wiring is invisible until a live dispatch 422, exactly the defect state this spec removes.
- **Phase 2:** Live dispatch verification costs bounded `gh` calls (dispatch, `run view`, `issue list`) — a red run, duplicate filing, or non-advancing state file is caught immediately. Skipping costs the entire spec's purpose — a structurally plausible but undeliverable workflow, a false PASS worse than the original defect.