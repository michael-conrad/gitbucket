# [SPEC] Wire GitHub Actions job into upstream-release-watch workflow

> **Full spec and artifacts: [`.issues/103/`](https://github.com/michael-conrad/gitbucket/tree/issues-data/.issues/103/)**

## Problem

The `upstream-release-watch` workflow (`.github/workflows/upstream-release-watch.yml`, merged via PR #102) declares `name`/`on(schedule+workflow_dispatch)`/`concurrency` but has **no `jobs:` section** — verified live via `gh workflow run` HTTP 422 "Required property is missing: jobs" on ref `dev`. The workflow cannot fire weekly or via manual dispatch. The fully tested pipeline script `scripts/release-watch.py` (polls upstream `releases/latest` `tag_name`, tag-shaped detection, dupe search, files one issue per new tag in michael-conrad/gitbucket via `GITHUB_TOKEN` `issues:write`, post-receipt monotonic state commit to `.github/release-watch/last-seen-tag` with `contents:write`) exists on `dev` with 16 passing enforcement tests but is **never invoked by any workflow job**.

## Approach

SC-1: workflow contains a `jobs:` section with one job that checks out the repo and runs `scripts/release-watch.py` in a Python/uv container passing `GITHUB_TOKEN` with `issues:write` and `contents:write` permissions — verified by parsing the workflow definition and asserting the jobs entry, checkout step, and script invocation exist (behavioral: pytest enforcement test).

SC-2: a `workflow_dispatch` run on GitHub succeeds end-to-end: dispatch succeeds (no 422), the job runs, and the run completes successfully with either zero filings (state up to date) or one issue filed for the current latest upstream tag plus a state-file commit advancing `.github/release-watch/last-seen-tag` — verified by `gh run view` observing a completed successful run on `dev` (behavioral: live GitHub Actions run inspection).

SC-3: a repeat `workflow_dispatch` run with state already current files zero issues — verified by observing a second completed run producing no new issue (behavioral: live run inspection).

## Affected Files

- `.github/workflows/upstream-release-watch.yml` (extend with `jobs:` section)
- `test/` (new workflow-job enforcement test)

## Related

- #101 — parent greenfield spec (RELATED-BUT-DISTINCT; this fixes its incomplete delivery)
- PR #102 — merged workflow + script on `dev`

🤖 OpenCode (ollama-cloud/glm-5.3-flash) created
