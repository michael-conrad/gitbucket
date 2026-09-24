# [SPEC] Weekly upstream-release watch job that files specs with dupe prevention

> **Full spec and artifacts: [`.issues/101/`](https://github.com/michael-conrad/gitbucket/tree/issues-data/.issues/101/)** — this issue is a condensed exec summary; the authoritative spec lives on the `issues-data` branch.
>
> **Local artifacts:** `.issues/101/` — analysis artifacts, SC summary

## Intent and Executive Summary

1. **Problem Statement**: GitBucket does not currently detect when the upstream gitbucket/gitbucket project publishes a new release, and does not automatically file a spec issue to track adopting that release. New upstream releases go unnoticed until manually checked.
2. **Root Cause / Motivation**: No scheduled job exists that polls upstream release information. `InitializeListener` (app startup hook) contains Quartz scheduling code that was previously disabled — a natural host for weekly job wiring but currently unused for this purpose.
3. **Approach Chosen**: Add a weekly Quartz-scheduled job wired in the app startup hook. The job polls the upstream GitHub `releases/latest` endpoint, extracts the release tag (e.g. `4.48.0`), diffs it against a locally persisted last-seen TAG, and — when a new tag is detected — files a spec issue via the existing issue-creation path, guarded by a dupe check.
4. **Alternatives Considered & Why Discarded**:
   - Polling raw commit SHAs via the commits feed — discarded: the binding developer constraint (2026-09-24) forbids treating commit hashes or intermediate commits as releases; only published release tags may trigger a ticket.
   - Webhooks on the upstream repo — discarded: upstream does not (and cannot be assumed to) notify this instance; polling is the only reliable mechanism.
5. **Key Design Decisions**:
   - Release detection is anchored to `releases/latest` → `tag_name` (live-verified 2026-09-24: latest published release tag is `4.48.0`, assets include `gitbucket.war`). Tradeoff: one network request per week, low load, must tolerate transient upstream failures by skipping without state change.
   - Dupe prevention uses a pre-file search plus post-file verification keyed on the release tag, rather than blind duplicate filing. Tradeoff: slightly slower than blind filing; guarantees idempotency across restarts and overlapping runs.
   - Job failure semantics: any upstream fetch failure results in a skipped run with NO state change — last-seen TAG is only advanced after a successful filing receipt. Tradeoff: a release may be re-detected and re-filed; the dupe guard absorbs this.
6. **User Intent / Original Prompt**: Weekly upstream-release watch job that files specs with duplicate prevention.

## Not Included

- **Automatic dependency build or deploy** — rationale: this spec covers detection and ticket filing only; building/testing against the new upstream release is separate work.
- **Non-release (pre-release/RC) tracking** — rationale: binding constraint permits filing only for published releases; intermediate states excluded.
- **Multi-upstream support** — rationale: only gitbucket/gitbucket is watched in this scope.

## Success Criteria

| ID | Criterion | Evidence Type | Verification Method |
|----|-----------|---------------|---------------------|
| SC-1 | A weekly job trigger SHALL be wired into the app startup hook. | behavioral | Test execution: instrumented scheduler test asserting the trigger fires on the weekly schedule |
| SC-2 | Overlapping job runs SHALL be serialized — no concurrent duplicate runs may interleave the dupe search or state advance. | behavioral | Test execution: second concurrent invocation is queued/serialized (instrumented scheduler test) |
| SC-3 | The job SHALL poll upstream `releases/latest`, extract `tag_name`, and diff it against the last-seen TAG to produce the set of NEW tags. | behavioral | Integration test with mocked upstream returning `tag_name: "4.48.0"`; diff produces the new-tag set |
| SC-4 | Commit hashes, intermediate commits, and any non-tag reference SHALL never be treated as a release (TAG-based detection only — binding constraint). | behavioral | Integration test: a commit-hash reference yields an empty new-tag set |
| SC-5 | For each new release tag, the job SHALL file exactly one spec issue via the existing issue-creation service. | behavioral | Integration test: filing via issue-creation service; one issue per tag observed |
| SC-6 | A repeated run with unchanged last-seen TAG SHALL file zero issues (idempotent). | behavioral | Test execution: two consecutive runs produce zero filings on the second run |
| SC-7 | A pre-filing dupe search keyed on the release tag SHALL skip filing for any tag already filed. | behavioral | Test execution: run against a store containing an already-filed tag produces no new issue for that tag |
| SC-8 | The last-seen TAG SHALL be persisted only after a successful filing receipt. | behavioral | Integration test: failed filing → no state advance; successful filing → state contains the filed tag |
| SC-9 | The last-seen TAG SHALL advance monotonically — never regressing to an older tag. | behavioral | Test execution: feeding an older tag after a newer one does not regress persisted state |
| SC-10 | The last-seen TAG SHALL be retained across application restarts. | behavioral | Integration test: restart the app → persisted state survives |

## Requirements

- R-1. The system SHALL schedule the upstream-release watch job on a weekly cadence from the app startup hook.
- R-2. The system SHALL detect upstream releases via release TAGS only (e.g. `4.48.0`) from the upstream `releases/latest` endpoint; commit hashes and intermediate commits SHALL NOT be treated as releases.
- R-3. The system SHALL file one spec issue per newly detected release tag via the existing issue-creation path.
- R-4. The system SHALL prevent duplicate filing by searching for a prior filing keyed on the release tag before creating an issue, and SHALL verify after filing.
- R-5. The system SHALL persist the last-seen TAG atomically and only after a confirmed successful filing, advancing it monotonically and retaining it across restarts. On first-ever run with an empty last-seen store, the system SHALL bootstrap deterministically by filing the current latest release tag and advancing state to it (no configuration knob, no skip branch).
- R-6. The system SHOULD treat upstream fetch failures as a skipped run with no state change, logging the failure.

## Items

### Item 1 (SC-1): Schedule wiring

- RED: enforcement test asserting a weekly trigger exists in startup wiring — fails before implementation
- GREEN: wire the weekly job schedule in the app startup hook
- verify: instrumented test that the trigger fires on the weekly schedule
- commit: startup wiring + test

### Item 2 (SC-2): Overlap serialization guard

- RED: enforcement test asserting concurrent runs serialize — fails before implementation
- GREEN: add an overlap guard so concurrent invocations queue/serialize
- verify: instrumented test that the second concurrent invocation is queued, not interleaved
- commit: overlap guard + test

### Item 3 (SC-3): Tag-diff detection pipeline

- RED: enforcement test asserting the poll/diff pipeline produces a new-tag set — fails before implementation
- GREEN: new upstream client fetching `releases/latest` → `tag_name`; diff against last-seen TAG state store
- verify: mocked upstream integration test — tag yields the new-release set
- commit: client + diff logic + test

### Item 4 (SC-4): Commit-hash-never-a-release exclusion

- RED: enforcement test asserting a commit-hash reference is never treated as a release — fails before implementation
- GREEN: detection path accepts only tag-shaped values from `tag_name`; non-tag references yield an empty new-tag set
- verify: integration test — a commit-hash reference yields an empty new-tag set
- commit: exclusion guard + test

### Item 5 (SC-5): Ticket filing

- RED: enforcement test asserting one issue per new tag is filed — fails before implementation
- GREEN: filing step calling the existing issue-creation service per new tag
- verify: integration test observing one filed issue per tag
- commit: filing logic + test

### Item 6 (SC-6): Repeat-run idempotency

- RED: enforcement test asserting a repeat run files zero issues — fails before implementation
- GREEN: idempotency semantics — unchanged last-seen TAG means zero filings
- verify: behavioral test — two consecutive runs, zero filings on second
- commit: idempotency logic + test

### Item 7 (SC-7): Pre-filing dupe search

- RED: enforcement test asserting an already-filed tag is skipped — fails before implementation
- GREEN: pre-file search by dupe key + skip when present
- verify: behavioral test — store containing an already-filed tag produces no new issue for it
- commit: guard logic + test

### Item 8 (SC-8): Post-receipt persistence

- RED: enforcement test asserting state does not advance on filing failure — fails before implementation
- GREEN: last-seen TAG store written atomically only after a confirmed filing receipt
- verify: integration test — failed filing leaves state unchanged; success writes the filed tag
- commit: state store + test

### Item 9 (SC-9): Monotonic advance

- RED: enforcement test asserting state cannot regress to an older tag — fails before implementation
- GREEN: monotonic advance rule in the state store
- verify: behavioral test — an older tag after a newer one does not regress persisted state
- commit: monotonic rule + test

### Item 10 (SC-10): Restart retention

- RED: enforcement test asserting state survives restart — fails before implementation
- GREEN: durable persistence of last-seen TAG across application restarts
- verify: integration test — restart retains state
- commit: durable store + test

## Dependencies

- **Reference:** `InitializeListener` (startup hook) — Relationship: wiring site for the weekly job — Status: exists, verified
- **Reference:** existing issue-creation service — Relationship: filing path reused as-is — Status: exists, verified
- **Reference:** issue-search service — Relationship: dupe-key search surface — Status: exists, verified
- **Reference:** Quartz scheduler availability in the app runtime — Relationship: scheduling mechanism — Status: pending verification during implementation

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
| Upstream releases/latest endpoint | API | https://api.github.com/repos/gitbucket/gitbucket/releases/latest | Live curl 2026-09-24 — latest release `4.48.0`, assets include `gitbucket.war` |
| App startup hook | code | `src/main/scala/gitbucket/core/servlet/InitializeListener.scala` | Read — Quartz scheduling code present (currently disabled) |
| Issue creation service | code | `src/main/scala/gitbucket/core/service/IssueCreationService.scala` | Read — `createIssue(owner, name, userName, title, content, ...)` |
| Issue search surface | code | `src/main/scala/gitbucket/core/service/IssuesService.scala` | Read — issue search by dupe key |
| Local release read model | code | `src/main/scala/gitbucket/core/service/ReleaseService.scala` | Read — no existing upstream poll code; new client required |

## Enforcement Gate

> **Enforcement gate:** All success criteria MUST pass before this spec is considered complete. Partial implementation is not permitted.

## Cost Frame

Cost is measured in defect-discovery-latency, not tool calls. Correctness is the only metric.

- SC-1: Verifying the weekly trigger with an instrumented scheduler test costs minutes. Skipping means a mis-wired job never fires, and releases go unnoticed — the exact defect this spec exists to prevent.
- SC-2: Verifying the overlap guard costs minutes. Skipping means concurrent runs interleave dupe search and state advance, producing duplicate filings discovered only in production tracking.
- SC-3: Running the mocked-upstream diff test costs minutes. Skipping means stale-tag mishandling ships silently and produces spurious release tickets discovered weeks later.
- SC-4: Verifying the commit-hash exclusion costs minutes. Skipping means raw commit hashes slip through as "releases" — a direct violation of the binding constraint with multiplied spurious tickets.
- SC-5: Running the one-issue-per-tag integration test costs minutes. Skipping means missing or multiplied filings are surfaced only by manual release checks, long after the defect was introduced.
- SC-6: Running the idempotency test costs minutes. Skipping means a repeat run files duplicate issues — correcting that costs review and cleanup of every duplicate.
- SC-7: Verifying the pre-filing dupe search costs minutes. Skipping means already-filed tags are re-filed on every re-detection, compounding duplicate cleanup.
- SC-8: Verifying post-receipt persistence (including the deterministic first-run bootstrap that files the current latest tag and advances state) costs minutes. Skipping means state advances on failed filings or the first-ever release is silently skipped forever.
- SC-9: Verifying monotonic advance costs minutes. Skipping means a regression to an older tag re-triggers filing for the entire back-range — a defensible-looking but exponentially costly cleanup.
- SC-10: Running the restart/retention integration test costs minutes. Skipping means lost state re-files entire release histories after each restart.

## Edge Cases

- **Input boundaries:** Upstream response missing `tag_name` or an empty load — the job SHALL treat it as a skipped run with no state change (input boundary).
- **State transitions:** First-ever run with an empty last-seen store — the job SHALL file the current latest release tag and advance state to it (deterministic bootstrap; no configuration knob, no skip branch). Rationale: the current latest upstream release is itself an actual release the developer wants a ticket for; skipping it would silently drop real releases. No partial states.
- **Failure modes:** Upstream unreachable/500 — run is skipped, last-seen TAG unchanged, failure logged (failure mode). Filing failure mid-run — the tag for which filing failed SHALL not advance last-seen state; retryable at next run (failure mode).
- **Concurrency:** Overlapping weekly runs (manual trigger plus scheduled) — overlap guard serializes runs so the dupe search and state advance are never interleaved (concurrency).
- **Recovery:** State write failure — the job SHALL retry the state advance before completing; failure to advance leaves the tag re-detectable next run, absorbed by the dupe guard (recovery).

## Change Control

| Date | Change | Reason | Authorized By |
|------|--------|--------|---------------|
| 2026-09-24 | Initial spec created | — | for_spec authorization |
| 2026-09-24 | Decomposed compound SCs SC-1/SC-2/SC-4/SC-5 into atomic sub-SCs; renumbered SC-1..SC-10; updated Items, Requirements traceability, Cost Frame, and sc-summary.yaml to match | Holistic validation aggregate FAIL — Compound-SC detection and Decomposition atomicity checks; each independently verifiable claim now maps to exactly one SC and one TDD item | for_spec pipeline (spec-creation revise) |
| 2026-09-24 | Defined the first-ever-run bootstrap policy deterministically: empty last-seen store SHALL file the current latest release tag and advance state (no config knob, no either/or); removed undefined "configured bootstrap policy" language from Edge Cases; updated R-5 and Cost Frame SC-8 consistently | Holistic validation aggregate FAIL iteration 2 — Completeness: Edge Cases "State transitions" deferred the bootstrap decision to an undefined policy; structural decision auto-resolved per pipeline gap-fill rules | for_spec pipeline (spec-creation revise) |

---

<!-- SPDX-FileCopyrightText: 2026 Michael Conrad -->
<!-- SPDX-License-Identifier: MIT -->
<!-- Provenance: AI-generated -->

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
