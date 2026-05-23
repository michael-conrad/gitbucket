# Synced from GitHub Issue #93 at 2026-05-23T05:00:00Z

## Purpose

Two fixes to the swagger documentation files:

1. **Add before/after annotation examples** — Section 11 currently shows only the annotated (after) state. Implementors need to see what a route looks like before and after Swagger annotations to understand the transformation. Three before/after pairs covering key endpoint types (GET with path params, GET with query params, POST with body param) will make the annotation pattern immediately clear.

2. **Remove .opencode-specific references** — The swagger docs currently reference issue #836, `critical-rules-060`, and `verification-before-completion`. These are internal tooling references that mean nothing outside this repository. The docs should use self-contained language that explains the principle without relying on external rule systems.