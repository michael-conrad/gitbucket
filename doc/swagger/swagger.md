# GitBucket Swagger Integration — Implementation Guide

This document is a **prescriptive implementation guide** for adding Swagger/OpenAPI 2.0 endpoint support to GitBucket's REST API. Every section describes **target state** — what must be built, how to build it, and how to verify it. Nothing in this document describes existing code unless explicitly noted.

The integration will expose the GitBucket REST API (served under `/api/v3`) as a machine-readable Swagger document at `GET /api-docs/swagger.json`.

---

## 1. Architecture Overview

Four components must be created and wired together:

1. **`GitBucketSwagger`** — a singleton `org.scalatra.swagger.Swagger` instance that will be shared by every API controller and by the docs servlet. Multiple `Swagger` instances silently produce empty/partial output, so this **must** be a singleton.

2. **`SwaggerResourcesApp`** — a `ScalatraServlet with JacksonJsonSupport with CorsSupport with SwaggerBase` that will serve `swagger.json`. It must override `bathPath` (an upstream typo in Scalatra-Swagger — not `basePath`) to return `Some("/api/v3")`, ensuring `swagger.json` contains `"basePath": "/api/v3"`. It will use `GitBucketSwagger` as its implicit `Swagger` and `gitbucket.core.api.JsonFormat.jsonFormats` as its JSON formats.

3. **`ApiControllerBase`** — the trait behind `ApiController` must be extended to mix in `SwaggerSupport` and bind `swagger = GitBucketSwagger`. It must also **manually** register the API resource against that `Swagger` in its `initialize` method with an **empty** `resourcePath` (`""`).

4. **`ScalatraBootstrap`** — must mount `SwaggerResourcesApp` at `/api-docs` directly on the `ServletContext`.

Key architectural constraints:

- `SwaggerResourcesApp` must be mounted directly on `ServletContext` at `/api-docs` — **never** inside `CompositeScalatraFilter`.
- `ApiControllerBase` runs inside `CompositeScalatraFilter`, which is incompatible with Scalatra's auto-discovery (`throwAFit`).
- Manual `swagger.register("api", "", ...)` in `ApiControllerBase.initialize` after `super.initialize(config)` — empty `resourcePath` prevents double-prefixing.
- `SwaggerResourcesApp` overrides `bathPath` (upstream typo, not `basePath`) to return `Some("/api/v3")`.

---

## 2. Wiring and Initialization

### 2.1 Dependency

Add `scalatra-swagger-javax` dependency to `build.sbt` (version 3.1.2).

### 2.2 ApiControllerBase

`ApiControllerBase` must be modified to:

1. Mix in `SwaggerSupport`
2. Override `swagger = GitBucketSwagger`
3. Override `initialize` to:
   - Call `super.initialize(config)` — **never skip this**; skipping it causes `NullPointerException` from `CorsSupport.corsConfig` on every `/api/v3/*` request
   - Print `System.err.println` banner brackets around the `super.initialize(config)` call so operators can identify the expected `throwAFit` trace
   - After `super.initialize`, call `swagger.register("api", "", Some(applicationDescription), this, swaggerConsumes, swaggerProduces, swaggerProtocols, swaggerAuthorizations)` with **empty** `resourcePath`
   - Log INFO for registration success, WARN for failure (does not halt servlet context)

### 2.3 ScalatraBootstrap

`ScalatraBootstrap` must mount `SwaggerResourcesApp` at `/api-docs` directly on `ServletContext` — not inside `CompositeScalatraFilter`.

```scala
context.mount(new SwaggerResourcesApp, "/api-docs")
```

### 2.4 Why empty resourcePath

Scalatra-Swagger computes each operation's path as `resourcePath + "/" + strippedRoutePath` where `strippedRoutePath` strips the route literal's leading slash. This means:

| `resourcePath` | Route literal | Path in `swagger.json` | Runtime dispatch |
|---|---|---|---|
| `"/api/v3"` | `/repos/:owner/:repository/issues` | `/api/v3/repos/{owner}/{repository}/issues` | Broken — relative routes fail under `CompositeScalatraFilter` |
| `"/api/v3"` | `/api/v3/repos/:owner/:repository/issues` | `/api/v3/api/v3/repos/{owner}/...` | Double-prefixed |
| `""` (empty) | `/api/v3/repos/:owner/:repository/issues` | `/api/v3/repos/{owner}/{repository}/issues` | Correct — single prefix, no double-prefixing |

The empty `resourcePath` approach keeps route path literals absolute (matching what GitBucket already uses), avoids double-prefixing, and preserves runtime dispatch under `CompositeScalatraFilter`.

### 2.5 Boot log

After implementation, the boot log must show:

```
[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/swagger.md
java.lang.IllegalStateException: I can't work out which servlet registration this is.
    at org.scalatra.swagger.SwaggerSupportSyntax.throwAFit(SwaggerSupport.scala:336)
    ... (rest of upstream trace)
[gitbucket] end expected scalatra-swagger init trace
[INFO] g.c.c.ApiControllerBase - Swagger manual registration succeeded for ApiControllerBase
```

If `Swagger manual registration failed` appears instead, the pipeline is broken; no annotation edit will help — fix the wiring first.

If a `NullPointerException` from `org.scalatra.CorsSupport.corsConfig` appears for any `/api/v3/*` request, `super.initialize(config)` is not being called. Restore it.

---

## 3. Annotation Rules

### 3.1 Inline pattern (mandatory for new annotations)

`apiOperation[T]("nickname")` must be placed **directly in the route's transformer list** — inline, not bound to a `val`:

```scala
get("/api/v3/repos/:owner/:repository/issues/:id",
  apiOperation[ApiIssue]("getIssue")
    .summary("Get a single issue")
    .description("Returns a single issue by its ID for the specified repository")
    .parameters(
      pathParam[String]("owner").description("Repository owner"),
      pathParam[String]("repository").description("Repository name"),
      pathParam[Int]("id").description("Issue number")
    )
    .responseMessages(ResponseMessage(404, "Issue not found"))) { ... }
```

Where a pre-existing val-bound operation already exists in the codebase (e.g., `val getApiRoot = apiOperation[...]; get("...", operation(getApiRoot))`), that val-bound instance may be retained — do not rewrite it solely for style consistency. All **new** annotations must use the inline pattern.

### 3.2 Nicknames must be globally unique

`apiOperation[T]("nickname")` — the nickname is the operation's primary key in Swagger. Duplicates collapse silently; the second silently overwrites the first. Names must be unique across the whole API, not just within a trait.

### 3.3 Path literals must be absolute

Route strings must keep their existing `/api/v3` prefix unchanged. The `resourcePath` in `swagger.register()` is empty (`""`), and `SwaggerResourcesApp` provides the `/api/v3` prefix via `bathPath` override. Do not strip `/api/v3` from route literals.

### 3.4 Models must be Swagger-friendly

- Use concrete case classes from `gitbucket.core.api.*` for response types
- Use `Option[T]` for optional fields
- Use `allowableValues(...)` on parameters that have a fixed value set (e.g., `state`, `direction`, `sort`)
- Define all parameters explicitly: `pathParam[T]`, `queryParam[T]` (or `.optional`), `bodyParam[Model]`, `headerParam[T]`
- Provide response metadata: success model/type and `responseMessages(...)` for error codes

### 3.5 Self-type / SwaggerSupport

`ApiControllerBase` will provide `override implicit lazy val swagger: Swagger = GitBucketSwagger`. Annotated traits should keep `& SwaggerSupport` in their self-type (it makes the DSL visible in the file) but **must not** redeclare an abstract `swagger` member — that shadows the concrete one and produces an empty `swagger.json`.

```scala
trait ApiIssueControllerBase extends ControllerBase {
  self: AccountService & IssuesService & IssueCreationService &
        MilestonesService & ReadableUsersAuthenticator &
        ReferrerAuthenticator & SwaggerSupport =>
  // no `protected implicit def swagger: Swagger` here
}
```

### 3.6 Required imports

```scala
import org.scalatra.swagger.{ResponseMessage, Swagger, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax._
```

---

## 4. Anti-Patterns

Mandatory prohibitions:

- **Do NOT** mount `SwaggerResourcesApp` inside `CompositeScalatraFilter`
- **Do NOT** skip `super.initialize(config)` in `ApiControllerBase`
- **Do NOT** redirect, suppress, or filter `System.err` to hide the expected `throwAFit` trace
- **Do NOT** introduce a second `Swagger` instance — everything must share `GitBucketSwagger`
- **Do NOT** register resource with `resourcePath = "/api/v3"` — use empty string `""` instead. `SwaggerResourcesApp` provides the `/api/v3` prefix via `bathPath` override
- **Do NOT** redeclare an abstract `swagger` member in any annotated trait
- **Do NOT** use val-bound pattern for **new** annotations (`val name = apiOperation[...]; get("...", operation(name))`) — use inline pattern instead. Pre-existing val-bound instances may be retained where they already exist in the codebase.
- **Do NOT** override `bathPath` in any class other than `SwaggerResourcesApp`
- **Do NOT** strip `/api/v3` from route path literals — they must remain absolute
- **Do NOT** annotate catch-all 404 handlers (e.g., `get("/api/v3/*")`) with `apiOperation[...]`
- **Do NOT** put authentication filters in front of `/api-docs/*` unless intentional — broken auth on the docs path looks identical to broken Swagger init

---

## 5. TDD Requirements

For each controller annotated in Phases 2 and 3:

- Create `*SwaggerSpec.scala` test files under `src/test/scala/gitbucket/core/servlet/`
- **RED**: Write test BEFORE adding annotations — test MUST fail initially
- **GREEN**: Implement minimum annotations to make RED test pass
- **REFACTOR**: Clean up while keeping tests green
- Verify `sbt test` passes with zero regressions after each controller

The anchor test file `CorsInitNpeSpec.scala` must be created as part of Phase 0/1 — it does not currently exist. Tests require a running container (`sbt ~container:start`); they hit a live server instance, not a mock. Do not move these tests to plain unit tests — the bugs they guard against only manifest end-to-end.

### TDD ground rules

- Always Red first. A test that passes on the first run proves nothing about the change you are about to make.
- One failing assertion at a time. If both wiring and annotation are broken, fix wiring first; an annotation test written against an empty `swagger.json` is a false negative.
- Keep new specs in `src/test/scala/gitbucket/core/servlet/` next to `CorsInitNpeSpec` and reuse its `httpGet` helper pattern for consistency.

### Evidence type classification for TDD tests

All TDD tests in this project verify runtime behavior (HTTP responses from a running Jetty/GitBucket server). Per the runtime-behavioral evidence classification gate (issue #836):

- Every `*SwaggerSpec.scala` test SC MUST be classified `behavioral` — the test sends HTTP requests to a live server and asserts on status codes, JSON responses, and header values
- Structural evidence (source file existence, code pattern matching) is INSUFFICIENT for these tests — the classification question is "does this change affect runtime behavior?" and the answer is always YES for Swagger endpoint additions
- If a behavioral test cannot execute (server unavailable, infrastructure failure), the SC verdict is FAIL per critical-rules-060 — NEVER substitute grep or structural checks for runtime verification

---

## 6. Validation Test Approach

The sibling document `doc/swagger/swagger-approach-validation.md` contains complete, self-contained instructions to recreate a standalone Jetty integration test that validates the approach (empty `resourcePath` + absolute route paths + `bathPath` override) without any GitBucket dependency.

Key validated properties:

- Empty `resourcePath` prevents double-prefixing (paths appear as `/api/v3/...` not `/api/v3/api/v3/...`)
- `bathPath` override produces correct `basePath: "/api/v3"` in `swagger.json`
- Manual `swagger.register` after `super.initialize(config)` works under `CompositeScalatraFilter`

---

## 7. Smoke Tests

Functional verification checks F1–F11 for pre-implementation and post-implementation regression testing.

**Phase 0/1 applicable (infrastructure must pass these):**

| # | What | Evidence Type | Command | Expected |
|---|------|---------------|---------|----------|
| F1 | Docs endpoint reachable | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api-docs/swagger.json` | `200` |
| F2 | `swagger.json` has populated `paths` | `behavioral` | `curl -sS $GB/api-docs/swagger.json \| jq '.paths \| keys \| length'` | positive integer |
| F3 | No double-prefixing | `behavioral` | `curl -sS $GB/api-docs/swagger.json \| jq '[.paths \| keys[] \| select(startswith("/api/v3/api/v3"))] \| length'` | `0` |

**Phase 1+ applicable (API routing must remain intact):**

| # | What | Evidence Type | Command | Expected |
|---|------|---------------|---------|----------|
| F4 | API root dispatches (no CORS NPE) | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3` | non-`500` |
| F5 | Auth-protected endpoint dispatches | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/user` | `401` |
| F6 | Authenticated endpoint returns JSON | `behavioral` | `curl -sS -u root:root $GB/api/v3/user \| jq '.login'` | `"root"` |
| F7 | Repo listing for authenticated user | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' -u root:root $GB/api/v3/user/repos` | `200` |
| F8 | Public repository listing | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/repositories` | `200` |
| F9 | Unknown repo returns 404, not 500 | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/repos/does/not/exist/issues/1` | `404` |
| F10 | CORS preflight does not 500 | `behavioral` | `curl -sS -o /dev/null -w '%{http_code}\n' -X OPTIONS -H 'Origin: http://example.com' -H 'Access-Control-Request-Method: GET' $GB/api/v3` | non-`500` |
| F11 | Spot-check annotated path parameters | `behavioral` | `curl -sS $GB/api-docs/swagger.json \| jq '.paths["/api/v3/repos/{owner}/{repository}/issues/{id}"].get.parameters \| map(.name)'` | array with `owner`, `repository`, `id` |

All F-checks are classified `behavioral` because they verify runtime behavior (HTTP responses from a running server). Structural evidence (e.g., confirming a file exists) or string evidence (e.g., grep for a pattern) is **insufficient** for these checks — they must be verified by executing the `curl` commands against a live GitBucket instance.

### Mandatory regression testing requirement

Run complete smoke tests F1–F11 **BEFORE any change** (pre-RED snapshot) and **AFTER verification-before-completion** (post-VBC snapshot). Diff the two snapshots. Every cell must match the "Expected" column post-change — do NOT submit if any regression is found.

### Setup

```bash
# Terminal 1: start container
sbt ~container:start
# Wait for: "[info] started o.e.j.s.h.ContextHandler@... {/,...}"

# Terminal 2: set environment variables
export GB=http://localhost:8080
export GB_AUTH='-u root:root'
```

### Pre-implementation snapshot (capture before changing anything)

```bash
mkdir -p /tmp/gb-swagger-pre
curl -sS -o /tmp/gb-swagger-pre/swagger.json -w '%{http_code}\n' \
  $GB/api-docs/swagger.json
curl -sS -o /tmp/gb-swagger-pre/user.json -w '%{http_code}\n' \
  $GB_AUTH $GB/api/v3/user
# ...one file per check below...
```

After implementing the change, repeat into `/tmp/gb-swagger-post/` and diff:

```bash
diff -ru /tmp/gb-swagger-pre /tmp/gb-swagger-post
```

For a pure refactor (no intended behavioral change) the diff should be empty for the API responses, and limited to expected additions in `swagger.json` (newly annotated routes).

### If the pre-implementation snapshot reveals a pre-existing API bug

If a check in the pre-implementation snapshot does not match the "Expected" column, a pre-existing bug exists on `main` — it is not caused by the annotation work.

Follow this workflow:

1. **Do NOT fix the bug.** A pre-existing API regression must be reviewed by a human before any code change.
2. **File a bug report** in `doc/swagger/bugs/` (directory must be created if it does not exist) as a Markdown file named after the failing check (e.g., `doc/swagger/bugs/F9-unknown-repo-returns-500.md`).
3. **Also file a GitHub issue** with a triage overview.
4. **Move on** to the next endpoint annotation. Do not gate annotation work on the filed bug unless it is a pipeline-level failure (F1, F2, F3, F4, F5, F9, F10 returning 500) — those are blockers because every annotation test downstream of a broken pipeline is a false negative.

### jq false-positive trap

`jq` renders absent JSON keys as `null`. Do not use `jq '{keyName, ...}'` to check whether a key exists — it fabricates the appearance of an explicit `null` value where the key is simply absent. Use `has("keyName")` to check presence (returns `true`/`false`), or `.keyName // "ABSENT"` to make absence explicit in the output.

---

## 8. Pre-existing Complementary Tests

The codebase already contains two test suites that complement the smoke tests.

### 8.1 ApiIntegrationTest.scala

File: `src/test/scala/gitbucket/core/api/ApiIntegrationTest.scala`

Uses `TestingGitBucketServer` to spin up a real Jetty instance with the full GitBucket webapp and exercises API endpoints via the `github-api` Java client (`org.kohsuke.github.GitHub`). Covers:

- Repository creation (covers F6, F7 surface area)
- Commit status CRUD (covers F4, F9 surface area)
- Content create/update (covers F6)
- Issue labels (covers F6)
- Git refs API (covers F9)

Run alongside smoke tests F4–F11 to validate API routing and response-body integrity. `ApiIntegrationTest` validates full JSON response structure (not just HTTP status), making it stronger than `curl`-based checks for detecting response regressions.

Run: `sbt "testOnly gitbucket.core.api.ApiIntegrationTest"` (requires `sbt package` first)

### 8.2 JsonFormatSpec.scala + ApiSpecModels.scala

Files: `src/test/scala/gitbucket/core/api/JsonFormatSpec.scala` and `src/test/scala/gitbucket/core/api/ApiSpecModels.scala`

25+ unit tests asserting every `Api*` case class (`ApiUser`, `ApiIssue`, `ApiRepository`, `ApiPullRequest`, `ApiCommit`, `ApiBranch`, etc.) serializes to exactly the expected JSON shape. These define the canonical JSON structure for all response models.

Swagger model definitions must match these same shapes. Any mismatch between a Swagger `definitions` entry and the `JsonFormat[T]`-verified JSON shape is a defect. When annotating a controller with `apiOperation[ApiIssue]`, the resulting Swagger `ApiIssue` definition must produce JSON matching `JsonFormat[ApiIssue]` output.

Run: `sbt "testOnly gitbucket.core.api.JsonFormatSpec"`

### 8.3 Regression testing protocol

Before and after any Swagger-related change:

1. Run `sbt test` — full test suite including `JsonFormatSpec`
2. Run `sbt "testOnly gitbucket.core.api.ApiIntegrationTest"` — integration tests (requires `sbt package` first)
3. Run smoke tests F1–F11 and diff against pre-implementation snapshot
4. All three must pass with zero regressions

---

## 9. Verification Checklist

Post-implementation verification steps with evidence type classifications per the runtime-behavioral evidence classification gate (issue #836). The classification question is substrate-determined: "does this step verify runtime behavior?" If YES, the evidence type is `behavioral` regardless of whether a cheaper structural check exists.

| # | Step | Evidence Type | Rationale |
|---|------|---------------|-----------|
| 1 | Build and run: `sbt clean` then `sbt ~container:start` | `behavioral` | Runtime: starts server, confirms compilation succeeds and server boots |
| 2 | Boot log shows expected banner-bracketed trace and `Swagger manual registration succeeded for ApiControllerBase` | `behavioral` | Runtime: verifies wiring executes correctly during servlet init |
| 3 | No `NullPointerException` from `CorsSupport` on `/api/v3/*` requests | `behavioral` | Runtime: verifies `super.initialize(config)` is called; an NPE manifests only at runtime |
| 4 | Docs endpoint serves valid Swagger 2.0 JSON with 200 status | `behavioral` | Runtime: HTTP response from live server |
| 5 | `basePath` is `/api/v3` (from `bathPath` override) | `behavioral` | Runtime: verified by reading `swagger.json` response from live server |
| 6 | No double-prefixing — no `/api/v3/api/v3` in any path | `behavioral` | Runtime: verified by reading `swagger.json` response from live server |
| 7 | Annotated routes use inline pattern (`apiOperation[...]` in route transformer list) | `string` | Code pattern: grep-verified, not runtime |
| 8 | No abstract `swagger` member in any `Api*ControllerBase` trait | `string` | Code pattern: grep-verified, not runtime |
| 9 | `sbt test` passes with zero regressions | `behavioral` | Runtime: test execution observes actual output |
| 10 | `ApiIntegrationTest` passes with zero regressions | `behavioral` | Runtime: integration test against live server |
| 11 | Pre/post smoke test diff shows no regressions | `behavioral` | Runtime: HTTP responses from live server captured before and after |

Steps 7 and 8 are `string` evidence — they verify code patterns, not runtime behavior. All other steps verify runtime behavior and are classified `behavioral`. Using structural evidence (e.g., "file exists") for behavioral steps is insufficient per the #836 classification gate.

---

## 10. Pre-existing Bug Handling

If smoke tests reveal a pre-existing API regression:

- **Do NOT fix the bug** — file a bug report in `doc/swagger/bugs/` (directory must be created if it does not exist)
- File a GitHub issue with triage overview
- Continue annotation work for unaffected endpoints
- Pipeline-level failures (F1, F2, F3, F4, F5, F9, F10 returning 500) are blockers — stop until resolved

---

## 11. Annotation Examples

Reference examples showing the inline pattern with absolute route paths:

```scala
get("/api/v3/repos/:owner/:repository/issues/:id", apiOperation[ApiIssue]("getIssue")
  .summary("Get a single issue")
  .description("Returns a single issue by its ID for the specified repository")
  .parameters(
    pathParam[String]("owner").description("Repository owner"),
    pathParam[String]("repository").description("Repository name"),
    pathParam[Int]("id").description("Issue number")
  )
  .responseMessages(ResponseMessage(404, "Issue not found"))) { ... }
```

```scala
get("/api/v3/user", apiOperation[ApiUser]("getAuthenticatedUser")
  .summary("Get the authenticated user")
  .description("Returns the authenticated user")
  .responseMessages(ResponseMessage(401, "Unauthorized"))) { ... }
```

```scala
post("/api/v3/repos/:owner/:repository/issues", apiOperation[ApiIssue]("createIssue")
  .summary("Create an issue")
  .description("Create a new issue in the specified repository")
  .parameters(
    pathParam[String]("owner").description("Repository owner"),
    pathParam[String]("repository").description("Repository name"),
    bodyParam[CreateIssuePayload].description("Issue data")
  )
  .responseMessages(
    ResponseMessage(201, "Issue created"),
    ResponseMessage(401, "Unauthorized"),
    ResponseMessage(422, "Validation failed")
  )) { ... }
```