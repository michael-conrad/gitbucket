> Full spec and plan artifacts: https://github.com/michael-conrad/gitbucket/tree/issues-data/107/

## Problem

The release-watch pipeline is silently inert: `scripts/release-watch.py` has NO entrypoint — no `if __name__ == '__main__'` block, no `main()`, and `run_release_watch()` has zero callers in the module. `uv run scripts/release-watch.py` imports the module, defines functions, exits 0 in 0.5s with zero output — nothing executes. GitHub Actions run 36086070831 (conclusion success, 9s) confirms: zero log output from the script step, zero issues filed for upstream tag 4.48.0, and state file `.github/release-watch/last-seen-tag` never created on dev. The 22 existing pytest tests never caught this because they all call functions directly with mocks — no test asserts the script is executable as `__main__`.

Two additional latent defects:
- (a) the workflow run step maps no `GITHUB_TOKEN` env so the script cannot authenticate even with an entrypoint;
- (b) the workflow has no step to commit/push the advanced state file back to dev so state persistence across runs cannot work.

## Scope

- Add `main()` + `if __name__ == '__main__'` guard to `scripts/release-watch.py` calling `run_release_watch()` with defaults.
- Pass `GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` to the workflow run step.
- Add workflow commit/push of `.github/release-watch/last-seen-tag` to dev after state advance.
- Add subprocess/executability pytest coverage.
- Workflow YAML parsing tests in `test/`.

**Out of scope:** changes to dupe-search logic, issue body templating, scheduling cadence, or upstream tag polling mechanics beyond wiring.

## Approach

Define `main()` in `scripts/release-watch.py` that invokes `run_release_watch()` with defaults (upstream repo gitbucket/gitbucket, target repo michael-conrad/gitbucket, state path `.github/release-watch/last-seen-tag`) under an `if __name__ == '__main__'` guard. Update `.github/workflows/upstream-release-watch.yml` to export the token and add a commit-push step for the state file. Add a subprocess test asserting the script performs work when executed, plus YAML-parsing tests for the env mapping. Live verification via dispatched workflow runs on dev.

## Fix Requirements

1. `scripts/release-watch.py` SHALL define a `main()` that calls `run_release_watch()` with defaults (upstream repo gitbucket/gitbucket, target repo michael-conrad/gitbucket, state path `.github/release-watch/last-seen-tag`) under an `if __name__ == '__main__'` guard — verified by pytest subprocess test executing the script with mocked environment asserting it performs work (behavioral: test execution).
2. The workflow run step SHALL pass env `GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` — verified by pytest parsing the workflow YAML asserting the env mapping (behavioral: test execution).
3. After a successful run with state advance, the workflow SHALL commit and push `.github/release-watch/last-seen-tag` to dev (git config safe, commit, push with `contents:write` token) — verified live by observing the state commit on dev after a dispatch (behavioral: live observation).
4. A live `workflow_dispatch` run on dev SHALL file exactly one issue for the current latest upstream tag and create the state file with that tag — verified live via `gh issue list` and contents API (behavioral: live observation).
5. A repeat dispatch SHALL file zero new issues (state current, dupe search) — verified live (behavioral: live observation).

## Impact

- **Risk: duplicate issues on first live run** — state file may not yet exist; dupe search mitigates.
- **Risk: token permission failures** — workflow needs `contents:write` on the state push; default token permissions must be verified.
- **Risk: repeat dispatch races** — state push ordering; single-job run avoids parallel dispatch conflicts.
- **Dependencies:** `secrets.GITHUB_TOKEN` availability, `gh`/git in container image.
- **Call to action:** approve spec to proceed with plan and implementation.

## Affected Files

- `scripts/release-watch.py`
- `.github/workflows/upstream-release-watch.yml`
- `test/` (new subprocess/executability test)

---
Root cause evidence: AST parse of `scripts/release-watch.py` + GitHub Actions run 36086070831 logs (live-verified, high confidence).

🤖 OpenCode (ollama-cloud/glm-5.3-flash) created
