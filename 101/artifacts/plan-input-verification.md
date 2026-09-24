# Plan Input Verification Ledger — Issue #101

Verified once (2026-09-24); all subsequent plan-composition steps re-read THIS ledger, not the sources.

## Issue State + Labels (from `.issues/101/issue.yaml`)

- remote_issue: 101
- status: open
- labels: `approved-for-for_pr`
- title: `[SPEC] Weekly upstream-release watch job that files specs with dupe prevention`
- remote_url: https://github.com/michael-conrad/gitbucket/issues/101

## SC List with Evidence Types (from `.issues/101/spec.md`)

All SCs are evidence type `behavioral`.

| SC | Criterion (condensed) | Verification Method |
|----|----------------------|---------------------|
| SC-1 | Workflow declares weekly `schedule:` cron + `workflow_dispatch` | Parse workflow definition asserting weekly cron entry and manual trigger |
| SC-2 | Workflow-level `concurrency` group serializes overlapping runs | Parse workflow definition asserting the concurrency group |
| SC-3 | Poll `releases/latest` → `tag_name`, diff against persisted last-seen TAG → new-tag set | Mocked-upstream integration test |
| SC-4 | Commit hashes / non-tag references never treated as releases | Integration test: commit-hash reference yields empty new-tag set |
| SC-5 | Exactly one spec issue filed per new tag in michael-conrad/gitbucket | Integration test: one issue created per new tag |
| SC-6 | Repeat run with unchanged last-seen TAG files zero issues | Two consecutive runs, zero filings on second |
| SC-7 | Pre-filing dupe search keyed on tag (title, open or closed) skips already-filed tags | Run against state containing an already-filed tag produces no new issue |
| SC-8 | State committed only after successful filing receipt; absent/empty state bootstraps by filing current latest tag then advancing | Integration test: failed filing → no state commit; success → state contains filed tag |
| SC-9 | Last-seen TAG advances monotonically, never regressing | Older tag after newer one does not regress persisted state |
| SC-10 | State retained across workflow runs; each run reads committed state file from checkout | Fresh run with committed state reads it; no-release run leaves state unchanged |

## Structure Artifact Mappings (from `.issues/101/artifacts/structure.yaml`)

| Phase | Name | SCs | Depends On |
|-------|------|-----|------------|
| phase-1 | Workflow skeleton (triggers + serialization) | SC-1, SC-2 | — |
| phase-2 | Tag-diff detection pipeline | SC-3, SC-4 | phase-1 |
| phase-3 | Filing and dupe prevention | SC-5, SC-6, SC-7 | phase-2 |
| phase-4 | State persistence | SC-8, SC-9, SC-10 | phase-3 |

- DAG: phase-1 → phase-2 → phase-3 → phase-4 (linear, no cycles).
- Triplet colocation: PASS; cross-phase dependency: PASS.
- Per-phase skill_task_selection: red → test-driven-development; green → test-driven-development; verify → verification-before-completion; commit → (orchestrator) commit-inline.

## Per-Task Cycle Steps (from implementation-workflow reference card)

Pre-implementation: `pre-regression`, `pre-regression-verify`.
Per item: `red` → `green` → `post-regression` → `verify` → `commit-inline`.
Post-implementation: `audit`, `z3-check`, `structural-checks`, `pre-pr-gate`, `regression-check`, `review-prep`, `create-pr`, `exec-summary`.

## CLI Surface Flags Needed

- `./.opencode/tools/local-issues update <repo>#101 --labels <comma-separated-list>` — REPLACES the entire labels array; every write must include all existing labels plus the new one. Current labels to preserve: `approved-for-for_pr`; write `approved-for-for_pr,spec-cleared`.

## Affected Files (from spec Scope)

- `.github/workflows/upstream-release-watch.yml` (new)
- `scripts/release-watch.py` (new, uv-managed inline script metadata)
- `.github/release-watch/last-seen-tag` (new committed state file)
- Explicitly NOT affected: `src/main/scala/**`
