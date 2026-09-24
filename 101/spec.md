# [SPEC] Weekly upstream-release watch job that files specs with dupe prevention

> **Full spec and artifacts: [`.issues/101/`](https://github.com/michael-conrad/gitbucket/tree/issues-data/.issues/101/)** — this issue is a condensed exec summary; the authoritative spec lives on the `issues-data` branch.
>
> **Local artifacts:** `.issues/101/` — analysis artifacts, SC summary

## Intent and Executive Summary

1. **Problem Statement**: GitBucket does not currently detect when the upstream gitbucket/gitbucket project publishes a new release, and does not automatically file a spec issue to track adopting that release. New upstream releases go unnoticed until manually checked.
2. **Root Cause / Motivation**: No scheduled watch exists, and the watch cannot live inside the GitBucket application — the requirement is that the job run ON GITHUB as a scheduled workflow under `.github/`. The initial in-app Quartz approach (job wired in `InitializeListener`, filing via in-app services) was rejected by developer correction (2026-09-24): it can never satisfy the requirement.
3. **Approach Chosen**: Add a greenfield GitHub Actions workflow (`.github/workflows/upstream-release-watch.yml`) with a `schedule:` POSIX cron trigger firing once per week (plus a `workflow_dispatch` manual trigger), guarded by a workflow-level `concurrency` group. The job runs in a Python/uv container (`ghcr.io/astral-sh/uv:python3.13-bookworm-slim`) and executes a uv-managed Python script (`scripts/release-watch.py`) that: (1) calls the upstream GitHub API `releases/latest` for gitbucket/gitbucket and extracts the release TAG (`tag_name`); (2) diffs it against the last-seen TAG persisted as a committed repo state file (`.github/release-watch/last-seen-tag`); (3) for each new tag, searches existing issues in michael-conrad/gitbucket for a prior filing keyed on that tag and opens exactly one new ticket when none exists; (4) commits the advanced state only after a confirmed filing receipt.
4. **Alternatives Considered & Why Discarded**:
   - In-app Quartz weekly job wired in `InitializeListener` with in-app issue-creation filing — DISCARDED (developer correction 2026-09-24): the job must run ON GITHUB as a scheduled workflow under `.github/`; no in-app scheduling mechanism can satisfy the requirement. All `src/main` in-app machinery is out of scope.
   - Polling raw commit SHAs via the commits feed — discarded: the binding developer constraint (2026-09-24) forbids treating commit hashes or intermediate commits as releases; only published release tags may trigger a ticket.
   - Webhooks on the upstream repo — discarded: upstream does not (and cannot be assumed to) notify this instance; polling is the only reliable mechanism.
   - Actions cache or Actions variables (cvars) as the state store — discarded: caches are evictable and not guaranteed to survive across runs; repo variables are mutable out-of-band with no audit trail. A committed state file is versioned, diffable, auditable, and deterministically read from each run's checkout.
5. **Key Design Decisions**:
   - Release detection is anchored to upstream `releases/latest` → `tag_name` (live-verified 2026-09-24: latest tag `4.48.0`, published `2026-09-20T07:42:02Z`). Tradeoff: one network request per week, low load, must tolerate transient upstream failures by skipping without state change.
   - State mechanism is a committed state file `.github/release-watch/last-seen-tag` holding exactly the last successfully filed tag. The workflow declares `permissions: issues: write` (filing) and `contents: write` (state commit) for its `GITHUB_TOKEN`. Tradeoff: one commit per detected release; the state is fully auditable in git history.
   - Dupe key is the release tag string verbatim in the title of an existing issue (open or closed) in michael-conrad/gitbucket, found via the Search issues API before filing. Tradeoff: one extra API call per new tag; guarantees idempotency across lost state and overlapping runs.
   - Overlap serialization uses a workflow-level `concurrency` group (default `cancel-in-progress: false`): at most one run of the group executes at any time; overlapping triggers pend rather than run concurrently.
   - Job failure semantics: any upstream fetch failure results in a skipped run with NO state change — last-seen TAG is only committed after a successful filing receipt, and the failure is visible in the workflow run logs. Tradeoff: a release may be re-detected and re-filed; the dupe guard absorbs this.
6. **User Intent / Original Prompt**: Weekly upstream-release watch job that files specs with duplicate prevention.

## Scope

Affected areas (greenfield — no `src/main` application code changes):

- `.github/workflows/upstream-release-watch.yml` — new GitHub Actions workflow: weekly `schedule:` cron trigger, `workflow_dispatch` manual trigger, workflow-level `concurrency` group, `GITHUB_TOKEN` permissions (`issues: write`, `contents: write`), Python/uv container job.
- `scripts/release-watch.py` — new uv-managed Python script (run via `uv run`, dependencies declared via inline uv script metadata): upstream poll, tag diff, dupe search, ticket filing, state advance.
- `.github/release-watch/last-seen-tag` — new committed state file holding the last successfully filed release tag.

Explicitly NOT affected: `src/main/scala/**` (no Quartz, no `InitializeListener`, no in-app services, no Java/Scala code).

## Not Included

- **Automatic dependency build or deploy** — rationale: this spec covers detection and ticket filing only; building/testing against the new upstream release is separate work.
- **Non-release (pre-release/RC) tracking** — rationale: binding constraint permits filing only for published releases; intermediate states excluded.
- **Multi-upstream support** — rationale: only gitbucket/gitbucket is watched in this scope.
- **Any in-app scheduling or filing code** (Quartz jobs, `InitializeListener` wiring, in-app issue-creation/search services, any Java/Scala change) — rationale: the job runs ON GITHUB as a scheduled workflow; the application is untouched.

## Success Criteria

| ID | Criterion | Evidence Type | Verification Method |
|----|-----------|---------------|---------------------|
| SC-1 | A GitHub Actions workflow SHALL declare a `schedule:` POSIX cron trigger firing once per week (plus a `workflow_dispatch` manual trigger). | behavioral | Test execution: parse the workflow definition asserting a weekly cron `schedule:` entry and a `workflow_dispatch` trigger exist |
| SC-2 | Overlapping workflow runs SHALL be serialized by a workflow-level `concurrency` group — no concurrent duplicate runs may interleave the dupe search or state advance. | behavioral | Test execution: parse the workflow definition asserting the `concurrency` group; platform semantics guarantee at most one run of the group executes at any time |
| SC-3 | The job SHALL call the upstream GitHub API `releases/latest` for gitbucket/gitbucket, extract `tag_name`, and diff it against the persisted last-seen TAG to produce the set of NEW tags. | behavioral | Integration test with mocked upstream returning `tag_name: "4.48.0"`; diff produces the new-tag set |
| SC-4 | Commit hashes, intermediate commits, and any non-tag reference SHALL never be treated as a release (TAG-based detection only — binding constraint). | behavioral | Integration test: a commit-hash reference yields an empty new-tag set |
| SC-5 | For each new release tag, the job SHALL file exactly one spec issue in michael-conrad/gitbucket via the GitHub API. | behavioral | Integration test: one issue created per new tag observed |
| SC-6 | A repeated run with unchanged last-seen TAG SHALL file zero issues (idempotent). | behavioral | Test execution: two consecutive runs produce zero filings on the second run |
| SC-7 | A pre-filing dupe search keyed on the release tag SHALL skip filing for any tag already filed — an existing issue (open or closed) in michael-conrad/gitbucket whose title contains the tag. | behavioral | Test execution: run against a state containing an already-filed tag produces no new issue for that tag |
| SC-8 | The last-seen TAG SHALL be persisted (committed to the state file) only after a successful filing receipt. | behavioral | Integration test: failed filing → no state commit; successful filing → state file contains the filed tag; absent/empty state file → bootstrap files the current latest tag then advances state |
| SC-9 | The last-seen TAG SHALL advance monotonically — never regressing to an older tag. | behavioral | Test execution: feeding an older tag after a newer one does not regress the persisted state |
| SC-10 | The last-seen TAG SHALL be retained across workflow runs — each run reads the committed state file from its checkout, and state persists when no new release is detected. | behavioral | Integration test: fresh run with a committed state file present reads it correctly; a no-release run leaves state unchanged |

## Requirements

- R-1. The system SHALL run the upstream-release watch as a GitHub Actions workflow in this repository (`.github/workflows/upstream-release-watch.yml`) triggered once per week by a `schedule:` POSIX cron trigger, with a `workflow_dispatch` manual trigger for on-demand runs.
- R-2. The system SHALL detect upstream releases via release TAGS only (e.g. `4.48.0`) from the upstream `releases/latest` endpoint (`GET /repos/gitbucket/gitbucket/releases/latest` → `tag_name`); commit hashes and intermediate commits SHALL NOT be treated as releases.
- R-3. The system SHALL file one spec issue per newly detected release tag in michael-conrad/gitbucket via the GitHub API, authenticated as the workflow's `GITHUB_TOKEN` (`issues: write`).
- R-4. The system SHALL prevent duplicate filing by searching existing issues in michael-conrad/gitbucket for a prior filing keyed on the release tag (tag string in the issue title, open or closed) before creating an issue, and SHALL verify after filing.
- R-5. The system SHALL persist the last-seen TAG as a committed repository state file (`.github/release-watch/last-seen-tag`) atomically and only after a confirmed successful filing, advancing it monotonically and retaining it across workflow runs (each run reads the committed state from its checkout). On first-ever run with an absent or empty state file, the system SHALL bootstrap deterministically by filing the current latest release tag and advancing state to it (no configuration knob, no skip branch).
- R-6. The system SHOULD treat upstream fetch failures as a skipped run with no state change, logging the failure in the workflow run logs.

## Items

### Item 1 (SC-1): Weekly workflow trigger

- RED: enforcement test asserting the workflow declares a weekly `schedule:` cron entry — fails before implementation
- GREEN: create `.github/workflows/upstream-release-watch.yml` with `on.schedule` (weekly POSIX cron, e.g. `23 4 * * 1`) plus `on.workflow_dispatch`
- verify: test parses the workflow definition and asserts the weekly schedule and manual trigger exist
- commit: workflow trigger + test

### Item 2 (SC-2): Overlap serialization guard

- RED: enforcement test asserting a workflow-level `concurrency` group exists — fails before implementation
- GREEN: add `concurrency: { group: upstream-release-watch }` (default `cancel-in-progress: false` — overlapping runs pend, never run concurrently)
- verify: test asserts the concurrency group; platform semantics guarantee single-run execution per group
- commit: concurrency guard + test

### Item 3 (SC-3): Tag-diff detection pipeline

- RED: enforcement test asserting the poll/diff pipeline produces a new-tag set — fails before implementation
- GREEN: `scripts/release-watch.py` fetches `releases/latest` → `tag_name` and diffs it against the state file read from the checkout
- verify: mocked-upstream integration test — tag yields the new-release set
- commit: client + diff logic + test

### Item 4 (SC-4): Commit-hash-never-a-release exclusion

- RED: enforcement test asserting a commit-hash reference is never treated as a release — fails before implementation
- GREEN: detection path accepts only tag-shaped values from `tag_name`; non-tag references yield an empty new-tag set
- verify: integration test — a commit-hash reference yields an empty new-tag set
- commit: exclusion guard + test

### Item 5 (SC-5): Ticket filing

- RED: enforcement test asserting one issue per new tag is filed in michael-conrad/gitbucket — fails before implementation
- GREEN: filing step in the script creating one issue per new tag via the GitHub API (`GITHUB_TOKEN`, `issues: write`)
- verify: integration test observing one filed issue per tag
- commit: filing logic + test

### Item 6 (SC-6): Repeat-run idempotency

- RED: enforcement test asserting a repeat run files zero issues — fails before implementation
- GREEN: idempotency semantics — unchanged last-seen TAG means zero filings
- verify: behavioral test — two consecutive runs, zero filings on second
- commit: idempotency logic + test

### Item 7 (SC-7): Pre-filing dupe search

- RED: enforcement test asserting an already-filed tag is skipped — fails before implementation
- GREEN: pre-file Search issues API query keyed on the tag (tag string in issue title, open or closed) + skip when present
- verify: behavioral test — state containing an already-filed tag produces no new issue for it
- commit: guard logic + test

### Item 8 (SC-8): Post-receipt persistence

- RED: enforcement test asserting state does not advance on filing failure — fails before implementation
- GREEN: state file written and committed (pushed with `contents: write`) only after a confirmed filing receipt
- verify: integration test — failed filing leaves state unchanged; success commits the filed tag; absent/empty state file bootstraps by filing the current latest tag then advancing
- commit: state store + test

### Item 9 (SC-9): Monotonic advance

- RED: enforcement test asserting state cannot regress to an older tag — fails before implementation
- GREEN: monotonic advance rule in the state update logic
- verify: behavioral test — an older tag after a newer one does not regress persisted state
- commit: monotonic rule + test

### Item 10 (SC-10): State retention across workflow runs

- RED: enforcement test asserting state survives a fresh run — fails before implementation
- GREEN: durable persistence via the committed state file; each run reads the committed state from its checkout
- verify: integration test — fresh run with committed state present reads it; no-release run leaves state unchanged
- commit: durable store + test

## Dependencies

- **Reference:** GitHub Actions `schedule:` trigger — Relationship: weekly execution mechanism — Status: platform capability, verified (workflow syntax docs, live fetch 2026-09-24)
- **Reference:** GitHub Actions `concurrency` groups — Relationship: overlap serialization — Status: platform capability, verified (workflow syntax docs, live fetch 2026-09-24)
- **Reference:** Workflow `GITHUB_TOKEN` with `issues: write` + `contents: write` — Relationship: filing and state-commit authorization — Status: platform capability, verified (workflow syntax permissions table, live fetch 2026-09-24)
- **Reference:** `ghcr.io/astral-sh/uv:python3.13-bookworm-slim` container image — Relationship: job runtime with uv available — Status: verified (GHCR manifest check 2026-09-24, HTTP 200)
- **Reference:** uv script metadata (`uv run scripts/release-watch.py`) — Relationship: dependency provisioning for the script — Status: verified (uv scripts docs, live fetch 2026-09-24)
- **Reference:** Upstream `releases/latest` endpoint — Relationship: detection source — Status: verified (live `gh api` 2026-09-24: `tag_name` `4.48.0`)
- **Reference:** GitHub Search issues API — Relationship: dupe-key search surface — Status: verified (live `gh api search/issues` 2026-09-24)

## Traceability

| Requirement | SC(s) | Phase(s) |
|-------------|-------|----------|
| R-1 | SC-1, SC-2 | Items 1, 2 |
| R-2 | SC-3, SC-4 | Items 3, 4 |
| R-3 | SC-5 | Item 5 |
| R-4 | SC-6, SC-7, SC-5 | Items 6, 7 |
| R-5 | SC-8, SC-9, SC-10 | Items 8, 9, 10 |
| R-6 | SC-3, SC-8 | Items 3, 8 |

## Documentation Sources

| Source | Type | Location | Verification |
|--------|------|----------|-------------|
| Upstream releases/latest endpoint | API | https://api.github.com/repos/gitbucket/gitbucket/releases/latest | Live `gh api` 2026-09-24 — `tag_name` `4.48.0`, `published_at` `2026-09-20T07:42:02Z` |
| GitHub Actions workflow syntax (`on.schedule`, `concurrency`, `jobs.<job_id>.container`, `permissions`) | docs | https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax | Live fetch 2026-09-24 — POSIX cron schedule (runs on default-branch tip, ≥5-min interval); concurrency groups allow only a single run at a time (queued runs pend by default, `cancel-in-progress` unset); job `container` key; `issues: write` permits creating issues, `contents: write` permits repo-content commits |
| GitHub Search issues API | API | https://api.github.com/search/issues | Live `gh api "search/issues?q=repo:michael-conrad/gitbucket+is:issue+in:title+4.48.0"` 2026-09-24 — returns `total_count` (dupe-key search surface) |
| uv script metadata | docs | https://docs.astral.sh/uv/guides/scripts/ | Live fetch 2026-09-24 — `uv run` executes standalone scripts; dependencies declared via inline script metadata |
| uv Python container image | registry | `ghcr.io/astral-sh/uv:python3.13-bookworm-slim` | Live GHCR manifest check 2026-09-24 — HTTP 200 (image exists) |

## Enforcement Gate

> **Enforcement gate:** All success criteria MUST pass before this spec is considered complete. Partial implementation is not permitted.

## Cost Frame

Cost is measured in defect-discovery-latency, not tool calls. Correctness is the only metric.

- SC-1: Verifying the weekly cron trigger with a workflow-definition test costs minutes. Skipping means a mis-declared schedule never fires (or fires too often), and releases go unnoticed — the exact defect this spec exists to prevent.
- SC-2: Verifying the concurrency group costs minutes. Skipping means overlapping runs interleave dupe search and state commits, producing duplicate filings or raced state writes discovered only in repo history.
- SC-3: Running the mocked-upstream diff test costs minutes. Skipping means stale-tag mishandling ships silently and produces spurious release tickets discovered weeks later.
- SC-4: Verifying the commit-hash exclusion costs minutes. Skipping means raw commit hashes slip through as "releases" — a direct violation of the binding constraint with multiplied spurious tickets.
- SC-5: Running the one-issue-per-tag integration test costs minutes. Skipping means missing or multiplied filings are surfaced only by manual release checks, long after the defect was introduced.
- SC-6: Running the idempotency test costs minutes. Skipping means a repeat run files duplicate issues — correcting that costs review and cleanup of every duplicate.
- SC-7: Verifying the pre-filing dupe search costs minutes. Skipping means already-filed tags are re-filed on every re-detection, compounding duplicate cleanup.
- SC-8: Verifying post-receipt persistence (including the deterministic first-run bootstrap that files the current latest tag and advances state) costs minutes. Skipping means state commits on failed filings or the first-ever release is silently skipped forever.
- SC-9: Verifying monotonic advance costs minutes. Skipping means a regression to an older tag re-triggers filing for the entire back-range — a defensible-looking but exponentially costly cleanup.
- SC-10: Running the cross-run retention integration test costs minutes. Skipping means lost state re-files entire release histories after each workflow run.

## Edge Cases

- **Input boundaries:** Upstream response missing `tag_name` or an empty load — the job SHALL treat it as a skipped run with no state change (input boundary).
- **State transitions:** First-ever run with an absent or empty state file — the job SHALL file the current latest release tag and advance (commit) state to it (deterministic bootstrap; no configuration knob, no skip branch). Rationale: the current latest upstream release is itself an actual release the developer wants a ticket for; skipping it would silently drop real releases. No partial states.
- **Failure modes:** Upstream unreachable/500 — run is skipped, last-seen TAG unchanged, failure visible in the workflow run logs (failure mode). Filing failure mid-run — the tag for which filing failed SHALL not advance last-seen state; retryable at next run (failure mode).
- **Concurrency:** Overlapping runs (manual `workflow_dispatch` plus scheduled) — the workflow-level `concurrency` group serializes runs so the dupe search and state commit are never interleaved; overlapping triggers pend rather than run concurrently (concurrency).
- **Recovery:** State commit failure — the job SHALL retry the state commit before completing; failure to commit leaves the tag re-detectable next run, absorbed by the dupe guard (recovery). State retention across runs IS the restart-retention semantic: because the state file is committed to the repository, every future run reads the persisted tag from its checkout.

## Change Control

| Date | Change | Reason | Authorized By |
|------|--------|--------|---------------|
| 2026-09-24 | Initial spec created | — | for_spec authorization |
| 2026-09-24 | Decomposed compound SCs SC-1/SC-2/SC-4/SC-5 into atomic sub-SCs; renumbered SC-1..SC-10; updated Items, Requirements traceability, Cost Frame, and sc-summary.yaml to match | Holistic validation aggregate FAIL — Compound-SC detection and Decomposition atomicity checks; each independently verifiable claim now maps to exactly one SC and one TDD item | for_spec pipeline (spec-creation revise) |
| 2026-09-24 | Defined the first-ever-run bootstrap policy deterministically: empty last-seen store SHALL file the current latest release tag and advance state (no config knob, no either/or); removed undefined "configured bootstrap policy" language from Edge Cases; updated R-5 and Cost Frame SC-8 consistently | Holistic validation aggregate FAIL iteration 2 — Completeness: Edge Cases "State transitions" deferred the bootstrap decision to an undefined policy; structural decision auto-resolved per pipeline gap-fill rules | for_spec pipeline (spec-creation revise) |
| 2026-09-24 | Replaced the in-app Quartz approach (weekly job wired in `InitializeListener`, filing via in-app issue-creation service, app-side state) with a greenfield GitHub Actions scheduled workflow: `.github/workflows/upstream-release-watch.yml` (`schedule:` weekly cron + `workflow_dispatch`, workflow-level `concurrency` group), a job in `ghcr.io/astral-sh/uv:python3.13-bookworm-slim` executing the uv-managed script `scripts/release-watch.py` (upstream `releases/latest` → `tag_name`, tag diff against the committed state file `.github/release-watch/last-seen-tag`, pre-filing dupe search via the Search issues API, issue filing in michael-conrad/gitbucket, post-receipt state commit with `contents: write`); rewrote SC-1..SC-10 descriptions to workflow/schedule/tag-diff/dupe/state semantics (count, atomicity, and behavioral evidence type preserved); added Scope section (affected areas `.github/workflows/`, `scripts/`, `.github/release-watch/`); updated Problem, Approach, Requirements R-1..R-6, Items, Dependencies, Documentation Sources, Cost Frame, Edge Cases (restart retention = state persistence across workflow runs), Not-Included, traceability, and sc-summary.yaml; removed all `src/main` in-app references | Developer correction (validation findings — CRITICAL approach defect): the job must run ON GITHUB as a scheduled workflow under `.github/`; the in-app Quartz approach can never satisfy the requirement | Developer (michael-conrad) via for_spec pipeline (spec-creation revise) |

---

<!-- SPDX-FileCopyrightText: 2026 Michael Conrad -->
<!-- SPDX-License-Identifier: MIT -->
<!-- Provenance: AI-generated -->

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
