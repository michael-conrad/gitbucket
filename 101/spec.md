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
| SC-1 | A weekly job trigger SHALL be wired into the app startup hook; overlapping runs SHALL be serialized (no concurrent duplicate runs). | behavioral | Test execution: trigger fires on weekly schedule; second concurrent invocation is queued/serialized (instrumented scheduler test) |
| SC-2 | The job SHALL poll upstream `releases/latest`, extract `tag_name`, compare against the last-seen TAG, and report the set of NEW tags; commit-hash or non-tag references SHALL never be treated as releases. | behavioral | Integration test with mocked upstream returning `tag_name: "4.48.0"`; a commit-hash reference yields an empty new-tag set |
| SC-3 | For each new release tag, the job SHALL file exactly one spec issue via the existing issue-creation service. | behavioral | Integration test: filing via issue-creation service; one issue per tag observed |
| SC-4 | A repeated run with unchanged last-seen TAG SHALL file zero issues (idempotent); a pre-file dupe search SHALL skip any tag already filed. | behavioral | Test execution: two consecutive runs produce zero filings on the second run |
| SC-5 | The last-seen TAG SHALL be persisted only after a successful filing receipt, SHALL advance monotonically, and SHALL be retained across application restarts. | behavioral | Integration test: restart the app → state retained; failed filing → no state advance |

## Requirements

- R-1. The system SHALL schedule the upstream-release watch job on a weekly cadence from the app startup hook.
- R-2. The system SHALL detect upstream releases via release TAGS only (e.g. `4.48.0`); commit hashes and intermediate commits SHALL NOT be treated as releases.
- R-3. The system SHALL file one spec issue per newly detected release tag via the existing issue-creation path.
- R-4. The system SHALL prevent duplicate filing by searching for a prior filing keyed on the release tag before creating an issue, and SHALL verify after filing.
- R-5. The system SHALL persist the last-seen TAG atomically and only after a confirmed successful filing, retaining it across restarts.
- R-6. The system SHOULD treat upstream fetch failures as a skipped run with no state change, logging the failure.

## Items

### Item 1 (SC-1): Schedule wiring

- RED: enforcement test asserting a weekly trigger exists in startup wiring — fails before implementation
- GREEN: wire the weekly job schedule in the app startup hook with an overlap guard
- verify: instrumented test that the trigger fires weekly and concurrent invocations serialize
- commit: startup wiring + test

### Item 2 (SC-2): Upstream poll + tag diff

- RED: enforcement test asserting a commit-hash reference is never treated as a release — fails before implementation
- GREEN: new upstream client fetching `releases/latest` → `tag_name`; diff against last-seen TAG state store
- verify: mocked upstream integration test — tag yields new-release set, commit hash yields empty set
- commit: client + diff logic + test

### Item 3 (SC-3): Ticket filing

- RED: enforcement test asserting one issue per new tag is filed — fails before implementation
- GREEN: filing step calling the existing issue-creation service per new tag
- verify: integration test observing one filed issue per tag
- commit: filing logic + test

### Item 4 (SC-4): Dupe guard

- RED: enforcement test asserting a repeat run files zero issues — fails before implementation
- GREEN: pre-file search by dupe key + skip when present
- verify: behavioral test — two consecutive runs, zero filings on second
- commit: guard logic + test

### Item 5 (SC-5): State persistence

- RED: enforcement test asserting state does not advance on filing failure — fails before implementation
- GREEN: last-seen TAG store with post-filing atomic advance and restart retention
- verify: integration test — restart retains state; failed filing leaves state unchanged
- commit: state store + test

## Dependencies

- **Reference:** `InitializeListener` (startup hook) — Relationship: wiring site for the weekly job — Status: exists, verified
- **Reference:** existing issue-creation service — Relationship: filing path reused as-is — Status: exists, verified
- **Reference:** issue-search service — Relationship: dupe-key search surface — Status: exists, verified
- **Reference:** Quartz scheduler availability in the app runtime — Relationship: scheduling mechanism — Status: pending verification during implementation

## Traceability

| Requirement | SC(s) | Phase(s) |
|-------------|-------|----------|
| R-1 | SC-1 | Item 1 |
| R-2 | SC-2 | Item 2 |
| R-3 | SC-3 | Item 3 |
| R-4 | SC-4, SC-3 | Item 4 |
| R-5 | SC-5 | Item 5 |
| R-6 | SC-2 | Item 2 |

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

- SC-1: Verifying the weekly trigger with an instrumented scheduler test costs minutes. Skipping means a mis-wired or double-firing job is discovered only after duplicate filings appear in production tracking.
- SC-2: Running the mocked-upstream integration test costs minutes. Skipping means commit-hash or stale-tag mishandling ships silently and produces spurious releases tickets discovered weeks later.
- SC-3: Running the one-issue-per-tag integration test costs minutes. Skipping means missing or multiplied filings are surfaced only by manual release checks, long after the defect was introduced.
- SC-4: Running the idempotency test costs minutes. Skipping means a repeat run files duplicate issues — correcting that costs review and cleanup of every duplicate.
- SC-5: Running the restart/retention integration test costs minutes. Skipping means stale or lost state re-files entire release histories after each restart — a defensible-looking but exponentially costly cleanup.

## Edge Cases

- **Input boundaries:** Upstream response missing `tag_name` or an empty load — the job SHALL treat it as a skipped run with no state change (input boundary).
- **State transitions:** First-ever run with empty last-seen store — the job SHALL file the current latest tag and advance state, or skip it without state change depending on configured bootstrap policy; no partial states.
- **Failure modes:** Upstream unreachable/500 — run is skipped, last-seen TAG unchanged, failure logged (failure mode). Filing failure mid-run — the tag for which filing failed SHALL not advance last-seen state; retryable at next run (failure mode).
- **Concurrency:** Overlapping weekly runs (manual trigger plus scheduled) — overlap guard serializes runs so the dupe search and state advance are never interleaved (concurrency).
- **Recovery:** State write failure — the job SHALL retry the state advance before completing; failure to advance leaves the tag re-detectable next run, absorbed by the dupe guard (recovery).

---

<!-- SPDX-FileCopyrightText: 2026 Michael Conrad -->
<!-- SPDX-License-Identifier: MIT -->
<!-- Provenance: AI-generated -->

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
