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

## Approach Chosen

Add a single `jobs:` section to the existing workflow containing one job that: checks out the repository, requests `GITHUB_TOKEN` permissions `issues:write` and `contents:write`, and runs `scripts/release-watch.py` in a container step whose image provides Python and `uv` (the repo's canonical tooling — verified against the repo's uv-based build/test commands). The existing `on:`/`concurrency:` scaffolding is preserved untouched. Verification is split in two: structural enforcement tests under `test/` that parse the workflow YAML (structure SCs), and live `workflow_dispatch` runs observed via `gh run view` on `dev` (behavioral SCs).

## Alternatives Considered & Why Discarded

- **Rewrite the workflow from scratch**: rejected — the existing `on`/`concurrency` scaffolding is correct; only the `jobs:` section is missing.
- **Run the script via a self-hosted runner**: rejected — no self-hosted runner is configured for this repo; GitHub-hosted runners with a Python/uv container satisfy the requirement.
- **Invoke the script inline via `run:` with embedded logic**: rejected — the tested script already exists at `scripts/release-watch.py`; duplicating its logic inline would break the single source of truth and evade its 16 enforcement tests.
- **Pin to a different Python version / uv installation step on the runner VM**: rejected — a container step with Python+`uv` available is the minimal, reproducible choice and keeps the runner VM environment out of the verification surface.

## Key Design Decisions

- **One job, two capability scopes**: the job requests `issues:write` (issue filing) and `contents:write` (state-file commit) — exactly the permissions `scripts/release-watch.py` needs, nothing broader. Tradeoff: no least-privilege isolation between the two scopes within the job, accepted because both are required for the same script invocation.
- **Checkout is a distinct SC**: the script lives in the repo, so a checkout step is required; it is verified separately from the script invocation to keep SCs atomic.
- **Live dispatch as behavioral verification**: HTTP 422 on dispatch is the current failure signature; a successful `workflow_dispatch` run observed via `gh run view` is the strongest available evidence that the wiring works. Structural inspection of the YAML alone is NOT accepted for these SCs.
- **Repeat-run idempotence is its own SC**: the script's dupe search + monotonic state file must yield zero filings on a second run; this is verified against a live second dispatch rather than inferred from unit tests.

## User Intent

The workflow must fire (weekly schedule or manual dispatch) and run the tested release-watch pipeline end-to-end: poll the latest upstream tag, file an issue for each new tag, and persist state so repeat runs are no-ops.

## Not Included

- **Changes to `scripts/release-watch.py`** — it is fully tested (16 passing enforcement tests) and merged via PR #102.
- **Any change to the schedule cadence (`on.schedule`) or `concurrency` settings** — the scaffolding is correct; modifying it expands blast radius without addressing the root cause.
- **Slack/email notifications, issue labelling changes, or multi-repo watch targets** — orthogonal to the missing `jobs:` wiring.
- **Backfilling historical tags missed before this fix** — out of scope; the script's state file only tracks the next tag going forward.

## Requirements

### Functional Requirements

- FR-1: The workflow file SHALL contain a `jobs:` section with at least one job.
- FR-2: The job SHALL check out the repository before invoking the script.
- FR-3: The job SHALL invoke `scripts/release-watch.py` in a container step whose image provides Python and `uv`.
- FR-4: The job SHALL declare `GITHUB_TOKEN` permissions `issues:write` and `contents:write`.
- FR-5: A `workflow_dispatch` trigger SHALL accept and start the workflow (no HTTP 422).
- FR-6: A dispatched run SHALL complete successfully on `dev` (conclusion `success`).
- FR-7: On first successful run with a new tag, the pipeline SHALL file exactly one issue in michael-conrad/gitbucket for the current latest upstream tag.
- FR-8: After filing, the pipeline SHALL commit the received tag to `.github/release-watch/last-seen-tag`.
- FR-9: A repeat dispatch with state current SHALL file zero new issues.

### Non-Functional Requirements

- NFR-1: The container step image SHALL be `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` (Python + `uv` — the repo's canonical build/test tooling is uv, per `AGENTS.md` build commands).
- NFR-2: Existing `on`/`concurrency` scaffolding SHALL be preserved unmodified.

## Items

Each SC maps to exactly one item. Each item carries the full TDD cycle.

### Item 1 (SC-1): Add `jobs:` section with one job

- RED: pytest enforcement test under `test/` parses `.github/workflows/upstream-release-watch.yml` and asserts a `jobs:` key with at least one entry — fails against current file (no `jobs:` section).
- GREEN: add `jobs:` section with one job (`release-watch`) to the workflow file.
- verify: run the enforcement test — PASS; confirm `on:`/`concurrency:` keys unchanged (diff shows only the `jobs:` addition).
- commit: `.github/workflows/upstream-release-watch.yml` + the enforcement test file.

### Item 2 (SC-2): Add checkout step

- RED: enforcement test asserts the job contains an `actions/checkout@*` step before any script-invocation step — fails (no job yet).
- GREEN: add `uses: actions/checkout@v4` as the first step of the job.
- verify: enforcement test PASS; checkout step precedes all other steps in the parsed step list.
- commit: same workflow file, same test file as Item 1.

### Item 3 (SC-3): Declare token permissions

- RED: enforcement test asserts the job declares `permissions:` with `issues: write` and `contents: write` — fails (absent).
- GREEN: add `permissions: { issues: write, contents: write }` to the job.
- verify: enforcement test PASS; no broader permission scopes present in the job.
- commit: same workflow file, same test file as Item 1.

### Item 4 (SC-4): Add script-invocation container step

- RED: enforcement test asserts the job has a step with `container`/image providing Python and `uv` that runs `scripts/release-watch.py` — fails (absent).
- GREEN: add a container step with image `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` whose entrypoint invokes `scripts/release-watch.py`.
- verify: enforcement test PASS; test also asserts the image string matches NFR-1 exactly.
- commit: same workflow file, same test file as Item 1.

### Item 5 (SC-5): Live dispatch accepted (no HTTP 422)

- RED: live probe — `gh workflow run upstream-release-watch.yml --ref dev` currently returns HTTP 422 (verified in Problem section); this observed 422 is the RED state.
- GREEN: Items 1-4's workflow file merged to `dev` makes dispatch accepted.
- verify: dispatch succeeds (exit 0, no 422) — live GitHub API call.
- commit: n/a (live verification; evidence recorded from the live run).

### Item 6 (SC-6): Dispatched run completes `success`

- RED: no dispatchable run exists — `gh run view` on the dispatched run would fail/absent before the fix.
- GREEN: the dispatched run from Item 5 completes with conclusion `success`.
- verify: `gh run view <run-id>` shows `status: completed`, `conclusion: success` — live GitHub API call.
- commit: n/a (live verification; evidence recorded from the live run).

### Item 7 (SC-7): First run files exactly one issue for the latest tag

- RED: before the fix, no run exists; the target issue for the current latest tag is absent from michael-conrad/gitbucket.
- GREEN: first successful dispatched run files exactly one issue for the current latest upstream tag.
- verify: `gh run view` + `gh issue list` in michael-conrad/gitbucket — exactly one new issue referencing the tag from `.github/release-watch/last-seen-tag` state progression.
- commit: n/a (live verification; evidence recorded from the live run).

### Item 8 (SC-8): State file committed

- RED: `.github/release-watch/last-seen-tag` not advanced by any run before the fix.
- GREEN: post-filing commit advances `.github/release-watch/last-seen-tag` to the received tag.
- verify: live observation of the state-file commit (git history of `.github/release-watch/last-seen-tag`) showing the received tag committed.
- commit: n/a (live verification; evidence recorded from the live run).

### Item 9 (SC-9): Repeat dispatch files zero new issues

- RED: no prior successful run with current state exists — a repeat dispatch cannot be observed before the fix.
- GREEN: a second dispatch with state current files zero issues.
- verify: second `gh run view` shows a completed run and `gh issue list` shows no new issue.
- commit: n/a (live verification; evidence recorded from the live run).

## Dependencies

| Reference | Relationship | Status |
|---|---|---|
| `scripts/release-watch.py` + its 16 enforcement tests (PR #102) | must be merged first — the job invokes it | satisfied (merged on `dev`) |
| `GITHUB_TOKEN` with `issues:write`/`contents:write` on `dev` workflow runs | must be available — the script uses it for filing and state commit | satisfied (repo default token) |
| GitHub Actions `workflow_dispatch` API reachable | must be reachable — dispatch and `gh run view` verification | verified live (HTTP 422 response proves API reachability) |

## Success Criteria

| SC | Criterion | Evidence Type | Verification Method |
|---|---|---|---|
| SC-1 | The workflow file SHALL contain a `jobs:` section with at least one job (FR-1) | behavioral | pytest enforcement test parsing the workflow YAML and asserting the `jobs:` entry exists |
| SC-2 | The job SHALL check out the repository before invoking the script (FR-2) | behavioral | pytest enforcement test asserting an `actions/checkout@*` step preceding the script-invocation step |
| SC-3 | The job SHALL declare `GITHUB_TOKEN` permissions `issues:write` and `contents:write` (FR-4) | behavioral | pytest enforcement test asserting the job's `permissions:` block contains exactly `issues: write` and `contents: write` |
| SC-4 | The job SHALL invoke `scripts/release-watch.py` in a container step with image `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` (FR-3, NFR-1) | behavioral | pytest enforcement test asserting the container step's image string and script reference |
| SC-5 | A `workflow_dispatch` on `dev` SHALL be accepted without HTTP 422 (FR-5) | behavioral | live dispatch via `gh workflow run` on `dev` — exit 0, no 422 |
| SC-6 | The dispatched run SHALL complete with conclusion `success` (FR-6) | behavioral | `gh run view` observing `status: completed`, `conclusion: success` |
| SC-7 | The first successful run SHALL file exactly one issue in michael-conrad/gitbucket for the current latest upstream tag (FR-7) | behavioral | `gh issue list` in michael-conrad/gitbucket after the run — exactly one new issue referencing the tag |
| SC-8 | The pipeline SHALL commit the received tag to `.github/release-watch/last-seen-tag` (FR-8) | behavioral | live observation of the state-file commit advance (git history of `.github/release-watch/last-seen-tag`) |
| SC-9 | A repeat dispatch with state current SHALL file zero new issues (FR-9) | behavioral | second `gh run view` completed run plus `gh issue list` showing zero new issues |

## Traceability

| SC | Requirement(s) | Item | Verification |
|---|---|---|---|
| SC-1 | FR-1, NFR-2 | I-1 | pytest enforcement test |
| SC-2 | FR-2 | I-2 | pytest enforcement test |
| SC-3 | FR-4 | I-3 | pytest enforcement test |
| SC-4 | FR-3, NFR-1 | I-4 | pytest enforcement test |
| SC-5 | FR-5 | I-5 | live `gh workflow run` dispatch |
| SC-6 | FR-6 | I-6 | `gh run view` live inspection |
| SC-7 | FR-7 | I-7 | `gh issue list` live inspection |
| SC-8 | FR-8 | I-8 | state-file commit live inspection |
| SC-9 | FR-9 | I-9 | second `gh run view`/`gh issue list` live inspection |

## Documentation Sources

| Source | Type | Location | Verification |
|---|---|---|---|
| `upstream-release-watch.yml` current state | code | `.github/workflows/upstream-release-watch.yml` on `dev` | read (live; no `jobs:` section) |
| `release-watch.py` pipeline script | code | `scripts/release-watch.py` on `dev` | read + PR #102 merge (16 passing enforcement tests) |
| GitHub Actions `workflow_dispatch` docs | doc | https://docs.github.com/en/actions/using-workflows/manually-running-a-workflow | live verification before implementation |
| GitHub Actions job `permissions` docs | doc | https://docs.github.com/en/actions/using-jobs/assigning-permissions-to-jobs | live verification before implementation |
| GitHub Actions container jobs docs | doc | https://docs.github.com/en/actions/using-jobs/running-jobs-in-a-container | live verification before implementation |
| `uv` container image reference | doc | https://github.com/astral-sh/uv-docker/pkgs/container/uv | live verification before implementation |
| PR #102 (workflow + script delivery) | code | https://github.com/michael-conrad/gitbucket/pull/102 | read (merged state on `dev`) |

## Enforcement Gate

> **Enforcement gate:** All success criteria MUST pass before this spec is considered complete. Partial implementation is not permitted.

## Cost Frame

Per-SC cost-frame statements (dark-prose-007; cost = defect-discovery-latency, not tool calls):

- **SC-1:** Adding the `jobs:` section costs one enforcement test cycle — defects in workflow structure surface at CI time, before any live run. Skipping costs one full discovery latency cycle — the missing `jobs:` entry is invisible until a live dispatch 422, which is exactly the defect state this spec exists to remove.
- **SC-2:** Adding the checkout step costs one test assertion — a missing checkout surfaces as an immediate "file not found" run failure. Skipping costs a full live-run cycle — the failure is discovered on the first dispatch, after the structural gate has already passed, doubling time-to-discovery.
- **SC-3:** Declaring token permissions costs one test assertion — missing permissions surface as a 403 mid-run. Skipping costs one live-run cycle plus one remediation re-run — the filing half of the pipeline fails after the polling half succeeded, masking the true failure point.
- **SC-4:** Pinning the container image and script invocation costs one test cycle — wrong image or wrong script path surfaces at CI time. Skipping costs a live-run discovery latency — the run fails at container pull or script resolution, discovered only on the first dispatch.
- **SC-5:** Live dispatch verification costs one `gh workflow run` call — the 422 signature is confirmed removed in seconds. Skipping costs the entire spec's purpose — the workflow remains undeliverable while structurally plausible, a false PASS worse than the original defect.
- **SC-6:** Observing run completion costs one `gh run view` call — a `failure` conclusion is caught immediately. Skipping costs one downstream cycle — a red run silently files no issues and the weekly schedule keeps failing unnoticed.
- **SC-7:** Verifying the single-issue filing costs one `gh issue list` call — duplicate or missing filings surface immediately. Skipping costs duplicate-issue noise or silent lost tags — the pipeline's primary deliverable is unverified.
- **SC-8:** Verifying the state-file commit costs one commit inspection — the monotonic-state invariant is proven live. Skipping costs state corruption — a non-advancing state file turns every subsequent weekly run into a duplicate storm, discovered only after a tag release window has passed.
- **SC-9:** Verifying the zero-dupe repeat dispatch costs one repeat dispatch plus one `gh issue list` call — idempotence is proven live. Skipping costs duplicate-issue noise — a wrongly-deduplicating pipeline files a new issue on every weekly run, discovered only after the schedule has fired multiple times.

Identity anchor: correctness is the only success metric — there is no score for live-run call count.

## Edge Cases

| Case | Handling |
|---|---|
| State file already current at dispatch time | Script's dupe search finds no new tag → zero filings; run still concludes success (SC-9) |
| New upstream tag published between runs | Next dispatch files exactly one issue and advances the state commit (SC-7, SC-8) |
| Upstream `releases/latest` unreachable at run time | Script's own fail-fast behavior applies (out of scope — script is unchanged) |
| Workflow dispatch 422 persists after fix | SC-5 FAIL → remediate before proceeding; structural evidence not accepted |

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
| 2026-09-24 | Split compound SCs into 8 atomic SCs (SC-2 → SC-2/SC-3 checkout|permissions; SC-4 → kept as single-dispatch observation SC-5/SC-6; SC-5 → SC-7/SC-8 filing|state+repeat); made FR-3 deterministic (container step with pinned `ghcr.io/astral-sh/uv:python3.12-bookworm-slim` image) and NFR-1 concrete (uv image named); added per-SC dark-prose-007 cost-frame statements; restructured Items to strict 1-SC-per-item with full RED/GREEN/verify/commit TDD detail; converted Documentation Sources to 4-column table; converted Dependencies to Reference/Relationship/Status with canonical all-or-nothing Enforcement Gate statement; added named Approach Chosen preamble section | second validation iteration: aggregate FAIL, 6 failing checks (compound SCs, disjunctive FR-3, vague NFR-1, missing per-SC cost frames, non-TDD Items, non-4-column Documentation Sources, non-canonical Dependencies/Enforcement Gate) | spec-creation revise (validation findings) |

| 2026-09-24 | Split compound SC-8 into two atomic SCs — SC-8 (state-file commit, FR-8) and new SC-9 (repeat dispatch files zero new issues, FR-9); split Item 8 into Item 8/Item 9; updated Traceability (SC-8/SC-9 rows), SC table, Edge Case references, and per-SC cost frames. Traced orphan requirement NFR-2 (preserve on/concurrency scaffolding unmodified) to SC-1 by adding NFR-2 to SC-1's requirement mapping (SC-1's verify step already asserts on:/concurrency unchanged) and SC-1 Traceability row | third validation iteration: aggregate FAIL, 3 failing checks (compound SC-8, orphan NFR-2 requirement trace) | spec-creation revise (validation findings) |

🤖 OpenCode (ollama-cloud/glm-5.3-flash) created