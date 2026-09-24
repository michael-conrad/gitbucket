# SPDX-FileCopyrightText: 2026 Michael Conrad
# SPDX-License-Identifier: MIT
# Provenance: AI-generated
"""Upstream release watch: poll gitbucket/gitbucket releases/latest, diff the
release TAG against the persisted last-seen tag, and produce the set of new
release tags (.issues/101 SC-3).

Co-authored with AI: OpenCode (zai-org/GLM-5.3-Flash)
"""

# /// script
# requires-python = ">=3.12"
# dependencies = []
# ///

import json
import os
import re
import urllib.parse
import urllib.request
from pathlib import Path

UPSTREAM_LATEST_URL = "https://api.github.com/repos/gitbucket/gitbucket/releases/latest"
DEFAULT_STATE_PATH = Path(".github/release-watch/last-seen-tag")


def fetch_upstream_latest_tag() -> str:
    """Fetch the upstream gitbucket/gitbucket latest release and return its
    tag_name. Raises on fetch failure or a missing/empty tag_name (skipped
    run, no state change)."""
    request = urllib.request.Request(
        UPSTREAM_LATEST_URL,
        headers={"Accept": "application/vnd.github+json"},
    )
    with urllib.request.urlopen(request) as response:
        payload = json.load(response)
    tag_name = payload.get("tag_name")
    if not isinstance(tag_name, str) or not tag_name.strip():
        raise ValueError(f"upstream releases/latest returned no tag_name: {payload!r}")
    return tag_name.strip()


def read_last_seen_tag(state_file: Path) -> str:
    """Read the persisted last-seen TAG from the committed state file.
    Absent or empty state file yields an empty string (bootstrap)."""
    if not state_file.exists():
        return ""
    return state_file.read_text(encoding="utf-8").strip()


def is_release_tag(value: str) -> bool:
    """Return True when the value is tag-shaped (a release tag like 4.48.0
    or v4.48.0). Commit-hash and other non-tag references yield False."""
    return bool(re.fullmatch(r"v?\d+(?:\.\d+)*[+-]?[\w.]*", value))


def compute_new_tags(state_file: Path = DEFAULT_STATE_PATH) -> list[str]:
    """Diff the upstream latest tag against the persisted last-seen TAG and
    return the set of NEW release tags (empty when nothing is new). Non-tag
    references (e.g. commit hashes) are never treated as releases."""
    upstream_tag = fetch_upstream_latest_tag()
    if not is_release_tag(upstream_tag):
        return []
    last_seen = read_last_seen_tag(state_file)
    if upstream_tag == last_seen:
        return []
    if last_seen and not is_newer_tag(upstream_tag, last_seen):
        return []
    return [upstream_tag]


def parse_tag_version(tag: str) -> tuple[int, ...]:
    """Parse a release tag into a comparable numeric version tuple
    (leading 'v' stripped, dotted numeric components)."""
    return tuple(int(part) for part in tag.lstrip("vV").split("."))


def is_newer_tag(candidate: str, current: str) -> bool:
    """Return True when ``candidate`` is strictly newer than the persisted
    ``current`` tag (monotonic advance rule, SC-9). An empty ``current``
    bootstraps: any candidate is newer."""
    if not current:
        return True
    return parse_tag_version(candidate) > parse_tag_version(current)


GITHUB_API_BASE = "https://api.github.com"


def create_issue(title: str, body: str, repo: str) -> dict:
    """Create one issue in ``repo`` via the GitHub API using GITHUB_TOKEN
    (issues: write). Returns the created-issue payload (number, html_url)."""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is not set — cannot file issue")
    url = f"{GITHUB_API_BASE}/repos/{repo}/issues"
    payload = json.dumps({"title": title, "body": body}).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def search_issue_exists(tag: str, repo: str = "michael-conrad/gitbucket") -> bool:
    """Return True when an issue already exists in ``repo`` (open or closed)
    whose title contains the release tag string — the pre-filing dupe key.
    Queries the GitHub Search issues API (live-verified surface)."""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("GITHUB_TOKEN is not set — cannot search issues")
    query = f"repo:{repo} is:issue in:title {tag}"
    url = f"{GITHUB_API_BASE}/search/issues?q={urllib.parse.quote(query)}"
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
        },
    )
    with urllib.request.urlopen(request) as response:
        payload = json.load(response)
    return int(payload.get("total_count", 0)) > 0


def file_new_tags(tags: list[str], repo: str = "michael-conrad/gitbucket") -> list[dict]:
    """File exactly one spec issue per new release tag in ``repo`` via the
    GitHub API. Returns a list of confirmed filing receipts (one per filed
    issue, each carrying the issue number and html_url)."""
    receipts: list[dict] = []
    for tag in tags:
        title = f"[SPEC] Upstream GitBucket release {tag} adoption tracking"
        body = (
            f"Upstream gitbucket/gitbucket published release tag `{tag}`.\n\n"
            f"Auto-filed by the upstream release watch job "
            f"(`.github/workflows/upstream-release-watch.yml`).\n"
        )
        receipt = create_issue(title, body, repo)
        if not isinstance(receipt, dict) or "number" not in receipt:
            raise ValueError(f"filing for tag {tag!r} produced no receipt: {receipt!r}")
        receipts.append(receipt)
    return receipts


def run_release_watch(
    state_file: Path = DEFAULT_STATE_PATH, repo: str = "michael-conrad/gitbucket"
) -> list[dict]:
    """Run the release-watch pipeline: diff the upstream latest TAG against the
    persisted last-seen TAG, skip tags whose dupe search finds an existing
    issue (open or closed) in ``repo`` keyed on the tag (SC-7), file one spec
    issue per remaining new release tag in ``repo``, and update the state file
    to the newest filed tag. Idempotent — a repeated run with an unchanged
    last-seen TAG files zero issues. Returns the filing receipts (empty on a
    no-op run)."""
    tags = compute_new_tags(state_file)
    last_seen = read_last_seen_tag(state_file)
    deduped: list[str] = []
    for tag in tags:
        if search_issue_exists(tag, repo):
            continue
        deduped.append(tag)
    tags = deduped
    receipts = file_new_tags(tags, repo)
    if tags:
        newest = last_seen
        for tag in tags:
            if is_newer_tag(tag, newest):
                newest = tag
        if is_newer_tag(newest, last_seen):
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(f"{newest}\n", encoding="utf-8")
    return receipts
