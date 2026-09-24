# Plan Input Verification Ledger — issue 103

Verified once at plan-creation entry (this file is the ledger; re-reading sources after this point is prohibited).

## Issue state (from `.issues/103/issue.yaml`, read this session)

- title: [SPEC] Wire GitHub Actions job into upstream-release-watch workflow
- status: open
- labels: [approved-for-for_pr]
- authorization_scope: for_pr → pipeline may run through pr_created; pr_strategy: stacked
- platform: github.com (owner michael-conrad, repo gitbucket)

## SC list with evidence types (from spec.md)

| SC | Evidence type | Method |
|---|---|---|
| SC-1 | behavioral | pytest enforcement test — `jobs:` section with ≥1 job (asserts on/concurrency unchanged → NFR-2) |
| SC-2 | behavioral | pytest — `actions/checkout@*` step precedes script invocation |
| SC-3 | behavioral | pytest — job `permissions:` exactly `issues: write` + `contents: write` |
| SC-4 | behavioral | pytest — container step, image `ghcr.io/astral-sh/uv:python3.12-bookworm-slim`, runs `scripts/release-watch.py` |
| SC-5 | behavioral | live `gh workflow run ... --ref dev` — exit 0, no 422 |
| SC-6 | behavioral | live `gh run view` — status completed, conclusion success |
| SC-7 | behavioral | live `gh issue list` — exactly one new issue for latest upstream tag |
| SC-8 | behavioral | live git history of `.github/release-watch/last-seen-tag` — commit advance |
| SC-9 | behavioral | second dispatch + `gh issue list` — zero new issues |

## Structure artifact mappings (from `.issues/103/artifacts/structure.yaml`)

- Phase 1 — workflow-structural-wiring: SC-1..SC-4, items I-1..I-4, full red/green/verify/commit cycles, files `.github/workflows/upstream-release-watch.yml` + `test/`
- Phase 2 — live-dispatch-verification: SC-5..SC-9, items I-5..I-9, commit n/a (live verification; evidence from live runs)
- DAG: phase-1 → phase-2 (single edge, no cycles)
- Post-implementation order: audit → z3-check (direct) → structural-checks → pre-pr-gate → regression-check → review-prep → create-pr → exec-summary

## Workflow reference card per-task cycle (from implementation-workflow.md)

- RED → GREEN → post-regression → verify → commit-inline (commit by orchestrator directly)
- Dispatch strings: red/green/post-regression via test-driven-development; verify via verification-before-completion; commit-inline orchestrator-direct
- Pre-implementation (once): pre-regression, pre-regression-verify
- Coercion: DONE_WITH_CONCERNS→FAIL; EVIDENCE_TYPE_MISMATCH→FAIL

## CLI surface needed

- `./.opencode/tools/local-issues update gitbucket#103 --labels ...` — REPLACES entire labels array; must include existing labels: `approved-for-for_pr` plus `spec-cleared`