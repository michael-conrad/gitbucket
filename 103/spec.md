# [SPEC] Wire GitHub Actions job into upstream-release-watch workflow

> **Full spec and artifacts: [`.issues/103/`](https://github.com/michael-conrad/gitbucket/tree/issues-data/.issues/103/)**

## Preamble

| Field | Value |
|---|---|
| Status | draft |
| Priority | high |
| Type | SPEC-FIX |
| Created | 2026-09-24 |
| Updated | 2026-09-24 |
| Parent | #101 (RELATED-BUT-DISTINCT) |

## Problem

The `upstream-release-watch` workflow (`.github/workflows/upstream-release-watch.yml`, merged via PR #102) declares `name`/`on(schedule+workflow_dispatch)`/`concurrency` but has **no `jobs:` section** — verified live via `gh workflow run` HTTP 422 "Required property is missing: jobs" on ref `dev`. The workflow cannot fire weekly or via manual dispatch. The fully tested pipeline script `scripts/release-watch.py` (polls upstream `releases/latest` `tag_name`, tag-shaped detection, dupe search, files one issue per new tag in michael-conrad/gitbucket via `GITHUB_TOKEN` `issues:write`, post-receipt monotonic state commit to `.github/release-watch/last-seen-tag` with `contents:write`) exists on `dev` with 16 passing enforcement tests but is **never invoked by any workflow job**.

## Root Cause

The workflow file was delivered (PR #102) with the `on:`/`concurrency:` scaffolding but the `jobs:` section was omitted — the script delivery and the workflow wiring were implemented as one PR, and the wiring half was never written. No workflow job exists to invoke `scripts/release-watch.py`, so the pipeline is dead code until a job is added.

## Alternatives Considered

- **Rewrite the workflow from scratch**: rejected — the existing `on`/`concurrency` scaffolding is correct; only the `jobs:` section is missing.
- **Run the script via a self-hosted runner**: rejected — no self-hosted runner is configured for this repo; GitHub-hosted runners with a Python container satisfy the requirement.
- **Invoke the script inline via `run:` with embedded logic**: rejected — the tested script already exists at `scripts/release-watch.py`; duplicating its logic inline would break the single source of truth and evade its 16 enforcement tests.
- **Pin to a different Python version / uv installation step**: considered — a Python container with `uv` available is the minimal, reproducible choice consistent with the repo's uv-based tooling.

## Key Design Decisions

- **One job, two capability scopes**: the job requests `issues:write` (issue filing) and `contents:write` (state-file commit) — exactly the permissions `scripts/release-watch.py` needs, nothing broader.
- **Checkout is a distinct SC**: the script lives in the repo, so a checkout step is required; it is verified separately from the script invocation to keep SCs atomic.
- **Live dispatch as behavioral verification**: HTTP 422 on dispatch is the current failure signature; a successful `workflow_dispatch` run observed via `gh run view` is the strongest available evidence that the wiring works.
- **Repeat-run idempotence is its own SC**: the script's dupe search + monotonic state file must yield zero filings on a second run; this is verified against a live second dispatch rather than inferred from unit tests.

## User Intent

The workflow must fire (weekly schedule or manual dispatch) and run the tested release-watch pipeline end-to-end: poll the latest upstream tag, file an issue for each new tag, and persist state so repeat runs are no-ops.

## Not Included

- Changes to `scripts/release-watch.py` itself (it is fully tested and merged).
- Any change to the schedule cadence (`on.schedule`) or `concurrency` settings.
- Slack/email notifications, issue labelling changes, or multi-repo watch targets.
- Backfilling historical tags missed before this fix.

## Requirements

### Functional Requirements

- FR-1: The workflow file SHALL contain a `jobs:` section with at least one job.
- FR-2: The job SHALL check out the repository before invoking the script.
- FR-3: The job SHALL invoke `scripts/release-watch.py` (directly or via a container step running it).
- FR-4: The job run SHALL have `GITHUB_TOKEN` permissions `issues:write` and `contents:write`.
- FR-5: A `workflow_dispatch` trigger SHALL accept and start the workflow (no HTTP 422).
- FR-6: A dispatched run SHALL complete successfully on `dev` (conclusion `success`).
- FR-7: On first successful run with a new tag, the pipeline SHALL file exactly one issue in michael-conrad/gitbucket for the current latest upstream tag.
- FR-8: After filing, the pipeline SHALL commit the received tag to `.github/release-watch/last-seen-tag`.
- FR-9: A repeat dispatch with state current SHALL file zero new issues.

### Non-Functional Requirements

- NFR-1: The job SHALL run in a Python/uv-capable environment consistent with repo tooling.
- NFR-2: Existing `on`/`concurrency` scaffolding SHALL be preserved unmodified.

## Items

| Item | Description | Maps To |
|---|---|---|
| I-1 | Add `jobs:` section with one job and token permissions to `.github/workflows/upstream-release-watch.yml` | SC-1, SC-2 |
| I-2 | Add checkout step to the job | SC-2 |
| I-3 | Add script-invocation step running `scripts/release-watch.py` in a Python/uv container | SC-3 |
| I-4 | New workflow-structure enforcement test under `test/` | SC-1, SC-2, SC-3 |
| I-5 | Verify live dispatch success, state commit, and no-dupe repeat | SC-4, SC-5, SC-6 |

## Dependencies

- `scripts/release-watch.py` and its 16 enforcement tests must exist on `dev` (already merged via PR #102).
- `GITHUB_TOKEN` with `issues:write`/`contents:write` available to workflow runs on `dev`.
- Remote GitHub API reachable for `workflow_dispatch` and `gh run view` verification.

## Success Criteria

| SC | Statement | Evidence Type | Verification Method |
|---|---|---|---|
| SC-1 | The workflow file SHALL contain a `jobs:` section with at least one job (FR-1) | behavioral | pytest enforcement test parsing the workflow YAML and asserting the `jobs:` entry exists |
| SC-2 | The job SHALL check out the repository before invoking the script and SHALL have `GITHUB_TOKEN` permissions `issues:write` and `contents:write` (FR-2, FR-4) | behavioral | pytest enforcement test asserting a checkout step and the required token permissions on the job |
| SC-3 | The job SHALL invoke `scripts/release-watch.py` in a Python/uv container (FR-3, NFR-1) | behavioral | pytest enforcement test asserting the script-invocation step referencing `scripts/release-watch.py` |
| SC-4 | A `workflow_dispatch` run on `dev` SHALL dispatch without HTTP 422 and complete with conclusion `success` (FR-5, FR-6) | behavioral | live GitHub Actions run inspection via `gh run view` observing a completed successful run |
| SC-5 | A successful first run SHALL file exactly one issue for the current latest upstream tag and commit the received tag to `.github/release-watch/last-seen-tag` (FR-7, FR-8) | behavioral | live run inspection: one issue filed plus state-file commit advancing `.github/release-watch/last-seen-tag` |
| SC-6 | A repeat `workflow_dispatch` with state already current SHALL file zero issues (FR-9) | behavioral | live run inspection: second `gh run view` completed run producing no new issue |

## Traceability

| SC | Requirement | Item | Verification |
|---|---|---|---|
| SC-1 | FR-1 | I-1, I-4 | pytest enforcement test |
| SC-2 | FR-2, FR-4 | I-1, I-2, I-4 | pytest enforcement test |
| SC-3 | FR-3, NFR-1 | I-3, I-4 | pytest enforcement test |
| SC-4 | FR-5, FR-6 | I-1, I-5 | `gh run view` live inspection |
| SC-5 | FR-7, FR-8 | I-3, I-5 | `gh run view` + issue/state-file observation |
| SC-6 | FR-9 | I-5 | second `gh run view` live inspection |

## Documentation Sources

- `.github/workflows/upstream-release-watch.yml` — current workflow state on `dev` (verified live; no `jobs:` section).
- `scripts/release-watch.py` — tested pipeline script on `dev` (16 passing enforcement tests, verified via PR #102 merge).
- GitHub Actions docs — `workflow_dispatch`, `jobs.<id>.permissions`, container jobs (live verification before implementation).
- PR #102 — merged workflow + script delivery.

## Enforcement Gate

- Workflow-structure SCs (SC-1..SC-3): enforced by a pytest enforcement test under `test/` that parses `.github/workflows/upstream-release-watch.yml` and asserts jobs entry, checkout step, script invocation, and token permissions.
- Live-run SCs (SC-4..SC-6): enforced by observing real `workflow_dispatch` runs via `gh run view` on `dev`; structural inspection of the workflow file alone is NOT accepted as evidence for these SCs.

## Cost Frame

| Cost Dimension | Assessment |
|---|---|
| Change size | Small — one workflow file extension plus one enforcement test |
| Verification cost | Three `workflow_dispatch` runs on `dev` (first run may file one issue + one state commit; repeat run files zero) |
| Failure blast radius | Confined to the workflow file and `test/`; no application code touched |
| Rollback | Revert the single workflow-file commit; script unchanged |

## Edge Cases

| Case | Handling |
|---|---|
| State file already current at dispatch time | Script's dupe search finds no new tag → zero filings; run still concludes success (SC-6) |
| New upstream tag published between runs | Next dispatch files exactly one issue and advances the state commit (SC-5) |
| Upstream `releases/latest` unreachable at run time | Script's own fail-fast behavior applies (out of scope — script is unchanged) |
| Workflow dispatch 422 persists after fix | SC-4 FAIL → remediate before proceeding; structural evidence not accepted |

## Affected Files

- `.github/workflows/upstream-release-watch.yml` (extend with `jobs:` section)
- `test/` (new workflow-job enforcement test)

## Related

- #101 — parent greenfield spec (RELATED-BUT-DISTINCT; this fixes its incomplete delivery)
- PR #102 — merged workflow + script on `dev`

## Change Control

| Date | Change | Reason | Authorized By |
|---|---|---|---|
| 2026-09-24 | Initial spec (drafted) | spec-creation create | — |
| 2026-09-24 | Restructured into full required section inventory (preamble fields, Root Cause, Alternatives, Key Design Decisions, User Intent, Not Included, Requirements, Items, Dependencies, Documentation Sources, Enforcement Gate, Cost Frame, Edge Cases); replaced compound SC-1/SC-2/SC-3 with atomic SC-1..SC-6 (workflow job structure, checkout+permissions, script invocation, live dispatch success, state commit/issue filing, no-dupe repeat) with evidence-type/verification-method columns; added Traceability table | aggregate FAIL validation: 10 failing checks (missing required sections, no SC table with evidence-type/verification-method columns, compound/disjunctive SCs, no traceability table) | spec-creation revise (validation findings) |

🤖 OpenCode (ollama-cloud/glm-5.3-flash) created