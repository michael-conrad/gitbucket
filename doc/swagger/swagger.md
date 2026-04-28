# GitBucket Swagger Integration

This document describes the Swagger / OpenAPI 2.0 integration **as it is
currently implemented and verified to work** in this codebase. It is the
single source of truth for the wiring; do not consult older sibling docs
(they have been removed in favor of this one).

The integration exposes the GitBucket REST API (served under `/api/v3`)
as a machine-readable Swagger document at `GET /api-docs/swagger.json`.

---

## 1. Architecture

Three pieces, in three files:

1. `gitbucket.core.controller.api.GitBucketSwagger` — a singleton
   `org.scalatra.swagger.Swagger` instance shared by every API
   controller and by the docs servlet. Multiple `Swagger` instances
   silently produce empty/partial output, so this **must** be a
   singleton.
2. `gitbucket.core.controller.api.SwaggerResourcesApp` — a
   `ScalatraServlet with JacksonSwaggerBase` that serves
   `swagger.json`. It uses `GitBucketSwagger` as its implicit
   `Swagger` and `gitbucket.core.api.JsonFormat.jsonFormats` as its
   JSON formats.
3. `gitbucket.core.controller.ApiControllerBase` (the trait behind
   `ApiController`) extends `SwaggerSupport`, binds
   `swagger = GitBucketSwagger`, and **manually** registers the
   `/api/v3` resource against that `Swagger` in its `initialize`.

`ScalatraBootstrap` wires them up:

- `context.mount(new SwaggerResourcesApp, "/api-docs")` — directly on
  the `ServletContext`, **never** inside `CompositeScalatraFilter`.
- `filter.mount(new ApiController, "/api/v3")` — inside
  `CompositeScalatraFilter`, like every other GitBucket controller.

These mount choices are deliberate. `SwaggerResourcesApp` lives outside
the composite filter so the docs endpoint is always reachable;
`ApiController` lives inside it so the API shares the same dispatch
model as the rest of GitBucket.

---

## 2. Why the API controller cannot rely on auto-discovery

Stock `scalatra-swagger` resolves a controller's mount path by walking
the servlet container's filter/servlet registrations. Under
`CompositeScalatraFilter`, only the composite is registered with the
container, so auto-discovery cannot resolve `/api/v3`. When
`SwaggerSupportSyntax.initialize` reaches that resolution step it
throws `IllegalStateException("I can't work out which servlet
registration this is.")` from its `throwAFit` helper.

Two important properties of that exception:

- It is **caught and printed** inside `SwaggerSupportSyntax.initialize`
  itself (`try { ... } catch { case e: Throwable => e.printStackTrace() }`).
  It never propagates out of `initialize`.
- We still need the rest of the Scalatra `initialize` chain to run
  (`ScalatraBase.initialize` sets `this.config`, `CorsSupport.initialize`
  seeds `CorsConfigKey`, etc.). Skipping `super.initialize` to avoid
  the trace causes a runtime `NullPointerException` from
  `CorsSupport.corsConfig` on every `/api/v3/*` request.

The implemented solution is therefore: **call `super.initialize(config)`,
let the harmless trace print, then register the resource manually.**

---

## 3. The implemented `initialize`

`src/main/scala/gitbucket/core/controller/ApiController.scala`:

```scala
override def initialize(config: ConfigT): Unit = {
  // super.initialize runs the full Scalatra init chain: sets this.config
  // (fixes CorsSupport NPE), seeds CorsConfigKey, etc. It also routes
  // through SwaggerSupportSyntax.initialize, which throws
  // IllegalStateException under CompositeScalatraFilter because it cannot
  // resolve the servlet registration. That exception is caught and
  // printed to System.err by scalatra-swagger itself — it never
  // propagates. The trace is expected and harmless; /api/v3 is
  // registered manually below.
  System.err.println(
    "[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/swagger.md"
  )
  super.initialize(config)
  System.err.println(
    "[gitbucket] end expected scalatra-swagger init trace"
  )

  try {
    swagger.register(
      "api", "/api/v3", Some(applicationDescription),
      this, swaggerConsumes, swaggerProduces, swaggerProtocols, swaggerAuthorizations
    )
    swaggerLogger.info("Swagger manual registration succeeded for ApiControllerBase")
  } catch {
    case e: Exception =>
      swaggerLogger.warn(s"Swagger manual registration failed: ${e.getMessage}", e)
  }
}
```

The `System.err.println` banner brackets the upstream stack trace so
operators reading the boot log can tell at a glance that the trace
between the lines is expected. Do not redirect or suppress
`System.err`; the trace must remain visible so future upstream
behavior changes are diagnosable.

---

## 4. Boot log — what "working" looks like

```
[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/swagger.md
java.lang.IllegalStateException: I can't work out which servlet registration this is.
    at org.scalatra.swagger.SwaggerSupportSyntax.throwAFit(SwaggerSupport.scala:336)
    ... (rest of upstream trace)
[gitbucket] end expected scalatra-swagger init trace
[INFO] g.c.c.ApiControllerBase - Swagger manual registration succeeded for ApiControllerBase
```

If `Swagger manual registration failed` appears instead, the pipeline
is broken; no annotation edit will help — fix the wiring first.

If a `NullPointerException` from `org.scalatra.CorsSupport.corsConfig`
appears for any `/api/v3/*` request, `super.initialize(config)` is not
being called. Restore it.

---

## 5. Adding Swagger metadata to API routes

Wiring (sections 1–4) only sets up the pipeline. For a route to appear
in `swagger.json` with parameters, models, and response info, the route
declaration itself must carry Swagger metadata.

### The one rule

`scalatra-swagger` only sees an operation if it is passed **as a route
transformer** at the time the route is declared. The DSL signature is:

```scala
get(transformers: RouteTransformer*)(action: => Any)
```

`apiOperation[T]("nickname")...` returns a `RouteTransformer`. It is
recorded on the route only when it appears in that `transformers` list.
Anywhere else (inside the action body, floating next to the route, etc.)
the call is built and discarded — the route is not annotated.

### Required pattern

Bind the operation to a `val`, then pass `operation(thatVal)` as a
transformer:

```scala
val getIssue =
  apiOperation[ApiIssue]("getIssue")
    .summary("Get a single issue")
    .description("Returns a single issue by its ID for the specified repository")
    .parameters(
      pathParam[String]("owner").description("Repository owner"),
      pathParam[String]("repository").description("Repository name"),
      pathParam[Int]("id").description("Issue number")
    )
    .responseMessages(ResponseMessage(404, "Issue not found"))

get("/repos/:owner/:repository/issues/:id", operation(getIssue))(referrersOnly { repository =>
  // ...action body unchanged...
})
```

Use the `val`-bound form for **every** route. Do not inline the
`apiOperation[...]` chain into the `get(...)` call, even for short
routes — uniformity across the ~150 endpoints is the point.

### Required imports

```scala
import org.scalatra.swagger.{ResponseMessage, Swagger, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax._
```

### Path literals must be relative

`ApiControllerBase.initialize` registers the resource with
`resourcePath = "/api/v3"`. `scalatra-swagger` prepends that to every
operation's path when it emits `swagger.json`. Therefore:

- The route literal **must not** start with `/api/v3`.
- It must start with `/` followed by the resource-relative path
  (e.g. `"/repos/:owner/:repository/issues/:id"`).

Runtime dispatch is unchanged because `ScalatraBootstrap` mounts the
controller at `/api/v3`, so a route declared as
`"/repos/:owner/..."` still serves `GET /api/v3/repos/:owner/...`.

If `swagger.json` shows any path under `/api/v3/api/v3/...`, the route
literal still has the prefix. Fix the literal — do **not** edit the
resource registration.

### Self-type / `SwaggerSupport`

`ApiControllerBase` already extends `SwaggerSupport` and provides
`override implicit lazy val swagger: Swagger = GitBucketSwagger`.
Annotated traits should keep `& SwaggerSupport` in their self-type
(it makes the DSL visible in the file) but **must not** redeclare an
abstract `swagger` member — that shadows the concrete one and
produces an empty `swagger.json`.

```scala
trait ApiIssueControllerBase extends ControllerBase {
  self: AccountService & IssuesService & IssueCreationService &
        MilestonesService & ReadableUsersAuthenticator &
        ReferrerAuthenticator & SwaggerSupport =>
  // no `protected implicit def swagger: Swagger` here
}
```

### Nicknames must be globally unique

`apiOperation[T]("nickname")` — the nickname is the operation's primary
key in Swagger. Duplicates collapse silently; the second silently
overwrites the first. Names must be unique across the whole API, not
just within a trait.

### Models must be Swagger-friendly

- Use concrete case classes from `gitbucket.core.api.*` for parameters
  and response types. Avoid raw `Map[String, Any]`, `JValue`, or
  sealed traits without an explicit discriminator — Swagger 2.0 cannot
  describe them well.
- Use `Option[T]` for optional fields.
- Use `allowableValues(...)` on parameters that have a fixed value set
  (e.g. `state`, `direction`, `sort`).
- Keep date/time types consistent with what the JSON formats actually
  serialize.

---

## 6. Red / Green TDD examples

Use these examples as templates whenever you change the Swagger
pipeline or annotate a new route. Each example follows the same
loop: write a failing assertion first (Red), make the smallest
change that turns it green (Green), then refactor.

The runnable anchor is
`src/test/scala/gitbucket/core/servlet/CorsInitNpeSpec.scala`. It
hits a live container (started via `sbt ~container:start`) and asserts
both the wiring (`SC-FIX-*`) and the docs payload (`SC-FIX-4`).

### Example A — wiring regression (the `CorsSupport` NPE)

This is the loop that produced the implemented `initialize` in
section 3.

**Red.** Before `super.initialize(config)` was being called, every
`/api/v3/*` request raised `NullPointerException` from
`org.scalatra.CorsSupport.corsConfig`. Encode that as a test:

```scala
test("SC-FIX-1b: /api/v3/repos/does/not/exist/issues/1 returns 404 not 500") {
  val (code, _) = httpGet("http://localhost:8080/api/v3/repos/does/not/exist/issues/1")
  assert(code != 500, s"got $code — CORS NPE regression")
}
```

Run `sbt ~container:start` in one shell; in another:

```bash
sbt "testOnly gitbucket.core.servlet.CorsInitNpeSpec"
```

With `super.initialize(config)` removed (or `this.config` otherwise
unset) the test fails with HTTP 500 — **Red**.

**Green.** Restore the documented `initialize` (section 3): call
`super.initialize(config)`, let the bracketed `throwAFit` trace
print, and follow with the manual `swagger.register(...)`. Re-run
the test — it passes — **Green**.

**Refactor.** Keep the `System.err.println` banner around
`super.initialize(config)` so future readers can see the harmless
upstream trace is expected, and confirm `SC-FIX-4` still asserts
`paths.nonEmpty` so a "fix" that empties `swagger.json` cannot
sneak in.

### Example B — annotating a new route

This is the loop to follow when adding Swagger metadata to any
route in `Api*ControllerBase`. Use `getIssue` from section 5 as the
worked example.

**Red.** Add a test (e.g. in `ApiSwaggerSpec` — create the file if
it does not yet exist) that asserts the route appears in
`swagger.json` with the expected parameters:

```scala
test("getIssue is annotated in swagger.json") {
  val (code, body) = httpGet("http://localhost:8080/api-docs/swagger.json")
  assert(code == 200)
  val json = JsonMethods.parse(body.get)
  implicit val formats: Formats = DefaultFormats
  val op = json \ "paths" \
    "/api/v3/repos/{owner}/{repository}/issues/{id}" \ "get"
  assert(op != JNothing, "getIssue path missing from swagger.json")
  val params = (op \ "parameters").extract[List[JValue]]
  val names  = params.map(p => (p \ "name").extract[String]).toSet
  assert(names == Set("owner", "repository", "id"),
         s"unexpected parameters: $names")
}
```

Before annotation, the route is dispatched at runtime but absent
from `swagger.json`; the assertion on the path or on `parameters`
fails — **Red**.

**Green.** In `ApiIssueControllerBase`, bind the operation to a
`val` and pass `operation(...)` as a route transformer (section 5):

```scala
val getIssue =
  apiOperation[ApiIssue]("getIssue")
    .summary("Get a single issue")
    .parameters(
      pathParam[String]("owner").description("Repository owner"),
      pathParam[String]("repository").description("Repository name"),
      pathParam[Int]("id").description("Issue number")
    )
    .responseMessages(ResponseMessage(404, "Issue not found"))

get("/repos/:owner/:repository/issues/:id", operation(getIssue))(
  referrersOnly { repository => /* unchanged action */ }
)
```

Restart the container, re-run the test — **Green**.

**Refactor.** Verify the standard anti-patterns from section 8 did
not creep in:

- The route literal does not start with `/api/v3` (no
  `/api/v3/api/v3/...` paths in `swagger.json`).
- The nickname (`"getIssue"`) is unique across the whole API.
- No `protected implicit def swagger: Swagger` was added to the
  trait.
- `apiOperation[...]` is referenced only via `operation(getIssue)`
  in the route's transformer list, never inside the action body.

### TDD ground rules for this codebase

- Tests in `CorsInitNpeSpec` require a running container; start
  `sbt ~container:start` in a separate shell, then
  `sbt "testOnly gitbucket.core.servlet.CorsInitNpeSpec"` (or the
  new spec) in another. Do not move these to plain unit tests —
  the bugs they guard against only manifest end-to-end.
- Always Red first. A test that passes on the first run is not
  proving anything about the change you are about to make.
- One failing assertion at a time. If both wiring and annotation
  are broken, fix the wiring (Example A) first; an annotation test
  written against an empty `swagger.json` is a false negative.
- Keep new specs in `src/test/scala/gitbucket/core/servlet/` next
  to `CorsInitNpeSpec` and reuse its `httpGet` helper pattern for
  consistency.

---

## 7. Functional smoke test — pre/post-implementation curl commands

Unit/spec coverage (section 6) proves the wiring and the docs payload.
The checklist below proves end-to-end that **the API endpoints still
dispatch at the correct path and still work** before and after any
Swagger-related change. Run it twice for any change that touches
`ApiController.initialize`, `ScalatraBootstrap`, `GitBucketSwagger`,
`SwaggerResourcesApp`, or any `Api*ControllerBase` route literal /
annotation:

1. Once **before** your change (the "pre-implementation" column).
2. Once **after** your change (the "post-implementation" column).

Both columns should match the "Expected (working)" output. Any cell
that does not match is a regression — fix it before submitting.

### Setup

In one shell (kept running for the duration of the checks):

```bash
sbt ~container:start
# Wait for: "[info] started o.e.j.s.h.ContextHandler@... {/,...}"
```

In another shell, the default credentials work for any auth-required
check:

```bash
export GB=http://localhost:8080
export GB_AUTH='-u root:root'
```

### Pre-implementation snapshot (capture before changing anything)

Run the curl commands in the next subsection with `$GB` pointed at
the **unchanged** build and save each response, e.g.:

```bash
mkdir -p /tmp/gb-swagger-pre
curl -sS -o /tmp/gb-swagger-pre/swagger.json -w '%{http_code}\n' \
  $GB/api-docs/swagger.json
curl -sS -o /tmp/gb-swagger-pre/user.json -w '%{http_code}\n' \
  $GB_AUTH $GB/api/v3/user
# ...one file per check below...
```

After implementing the change, repeat into `/tmp/gb-swagger-post/`
and diff:

```bash
diff -ru /tmp/gb-swagger-pre /tmp/gb-swagger-post
```

For a pure refactor (no intended behavioral change) the diff should
be empty for the API responses, and limited to the expected
additions in `swagger.json` (newly annotated routes).

### Required checks

| # | What it proves | Command | Expected (working) |
|---|----|----|----|
| F1 | Docs endpoint reachable, populated | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api-docs/swagger.json` | `200` |
| F2 | `swagger.json` has populated `paths` | `curl -sS $GB/api-docs/swagger.json \| jq '.paths \| keys \| length'` | a positive integer |
| F3 | No double-prefixing in docs | `curl -sS $GB/api-docs/swagger.json \| jq '[.paths \| keys[] \| select(startswith("/api/v3/api/v3"))] \| length'` | `0` |
| F4 | API root dispatches (no CORS NPE) | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3` | non-`500` (typically `404`) |
| F5 | Auth-protected endpoint dispatches | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/user` | `401` (without auth) |
| F6 | Same endpoint authenticates and returns JSON | `curl -sS $GB_AUTH $GB/api/v3/user \| jq '.login'` | `"root"` |
| F7 | Repo listing for the authenticated user | `curl -sS -o /dev/null -w '%{http_code}\n' $GB_AUTH $GB/api/v3/user/repos` | `200` |
| F8 | Public repository listing | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/repositories` | `200` |
| F9 | Unknown repo returns `404`, not `500` | `curl -sS -o /dev/null -w '%{http_code}\n' $GB/api/v3/repos/does/not/exist/issues/1` | `404` |
| F10 | CORS preflight does not 500 | `curl -sS -o /dev/null -w '%{http_code}\n' -X OPTIONS -H 'Origin: http://example.com' -H 'Access-Control-Request-Method: GET' $GB/api/v3` | non-`500` |
| F11 | Spot-check an annotated path's parameters | `curl -sS $GB/api-docs/swagger.json \| jq '.paths["/api/v3/repos/{owner}/{repository}/issues/{id}"].get.parameters \| map(.name)'` | array containing `"owner"`, `"repository"`, `"id"` |

Notes on the table:

- F4/F5/F9/F10 guard the `CorsSupport` NPE regression — they must
  return non-`500` both pre and post. If F4 returns `500` **only**
  after your change, you have broken `super.initialize(config)`
  (see section 3).
- F3 guards path-literal hygiene — if it returns a non-zero count
  after your change, you added a route literal that still starts
  with `/api/v3` (see section 5, "Path literals must be relative").
- F11 will return `null` until that specific route is annotated.
  That is expected for unannotated endpoints; what must **not**
  change between pre and post is whether the runtime call to that
  endpoint still works (covered by F9).

### If the pre-implementation snapshot reveals a pre-existing API bug

The pre-implementation snapshot's purpose is to establish the
baseline behavior of the **unchanged** build. If a check in that
column does **not** match the "Expected (working)" output, you have
found a bug that already exists on `main` — it is **not** caused
by the annotation work you are about to do.

When that happens, follow this workflow strictly:

1. **Do not fix the bug.** A pre-existing API regression has to be
   reviewed by a human before any code change. Silently fixing it
   inside an annotation PR conflates two unrelated changes and
   makes the diff impossible to review.
2. **Do not abandon the annotation task either.** The annotation
   work is independent of the bug; skipping it just because an
   unrelated endpoint misbehaves loses progress.
3. **File a bug report** in `doc/swagger/bugs/` (create the
   directory if it does not exist) as a new Markdown file named
   after the failing check, e.g.
   `doc/swagger/bugs/F9-unknown-repo-returns-500.md`. The report
   must contain:
   - **Title** — one-line summary (e.g. "F9: unknown repo returns
     500 instead of 404").
   - **Detected during** — the pre-implementation run for which
     annotation task (the endpoint / `Api*ControllerBase` you were
     about to annotate).
   - **Check ID** — the row from the section 7 table (`F1`–`F11`)
     or, for an off-table check, the exact `curl` command.
   - **Reproduction** — the exact `curl` command, the observed
     response (status code + body excerpt), and the expected
     response from the "Expected (working)" column.
   - **Scope** — which endpoint(s)/path(s) are affected; whether
     the failure is auth-dependent; whether it reproduces on a
     clean `target/gitbucket_home_for_test`.
   - **Suspected cause** — your best read of where the bug lives
     (controller / service / route literal / wiring), with file
     and line references where possible. State explicitly that
     this is a hypothesis, not a verified diagnosis.
   - **Possible fixes** — one or more candidate fixes, each with
     trade-offs. Explicitly mark them as *unverified proposals*.
     Do not implement any of them.
   - **Status** — `Open — awaiting human review`.
4. **Move on to the next endpoint that needs annotation.** Pick
   the next `Api*ControllerBase` route from the annotation backlog
   and run the section 6 / section 7 loop against it. Do **not**
   gate annotation work on the filed bug being resolved unless
   the bug is in the Swagger pipeline itself (sections 1–4) — in
   that case, the pipeline regression has to be fixed first,
   because every annotation test downstream of it would be a false
   negative (see section 6, "TDD ground rules").
5. **Reference the bug from your annotation PR.** In the PR
   description, list any `doc/swagger/bugs/*.md` reports that
   were filed during the pre-implementation snapshot so reviewers
   can see the baseline failures are known and out of scope for
   this PR.

A pipeline-level failure (F1, F2, F3, F4, F5, F9, F10 returning
`500` or matching the "Broken" column for the **wiring**, not the
endpoint logic) is the one exception: those are blockers because
they make every annotation test below them meaningless. File the
bug report **and** stop annotation work until a human resolves
the pipeline regression.

### Interpreting the diff

| Pre | Post | Meaning |
|----|----|----|
| Working | Working | No regression; ship it. |
| Working | Broken | Your change regressed wiring or routing. Do not submit. |
| Broken  | Working | Your change is the fix; the pre-snapshot is the documented Red. |
| Broken  | Broken | Your change did not address the failure; iterate. |

A "working" run means every cell in the table matches the
"Expected" column. Any other state is "broken" for the purposes of
this checklist.

---

## 8. Verification

1. Build / run per `doc/build.md` (e.g. `sbt ~container:start`).
2. Boot log shows the expected banner-bracketed trace and
   `Swagger manual registration succeeded for ApiControllerBase`.
3. No `NullPointerException` from `org.scalatra.CorsSupport` on
   `/api/v3/*` requests.
4. Docs endpoint serves:

   ```bash
   curl -s http://localhost:8080/api-docs/swagger.json | jq '{basePath, paths: (.paths|keys)}'
   ```

   - HTTP 200, JSON body.
   - `paths` lists every annotated route under `/api/v3/...` (single
     prefix, never `/api/v3/api/v3/...`).
   - Routes that have not yet been annotated (section 5) will be
     absent — that is expected; annotate them to make them appear.

5. Spot-check a single annotated path's parameters:

   ```bash
   curl -s http://localhost:8080/api-docs/swagger.json \
     | jq '.paths["/api/v3/repos/{owner}/{repository}/issues/{id}"].get.parameters'
   ```

   Expected: an array with the declared `path` / `query` / `body`
   parameters and their declared types.

6. Run the functional smoke test (section 7) pre and post change
   and diff the two snapshots; every cell must match the
   "Expected (working)" column post-change.

---

## 9. Do not

- Do not mount `SwaggerResourcesApp` inside `CompositeScalatraFilter`.
- Do not put authentication filters in front of `/api-docs/*` unless
  intentional — broken auth on the docs path looks identical to a
  broken Swagger init.
- Do not skip `super.initialize(config)` in `ApiControllerBase`.
- Do not redirect, suppress, or filter `System.err` to hide the
  expected `throwAFit` trace.
- Do not "just catch the NPE" in `CorsSupport` or a wrapper filter —
  the fix is to make `super.initialize` run.
- Do not introduce a second `Swagger` instance; everything must share
  `GitBucketSwagger`.
- Do not register the `/api/v3` resource with a different
  `resourcePath` (e.g. `"/"`) as a way to "fix" double-prefixing.
  The resource path is `/api/v3`; route literals are relative.
- Do not redeclare an abstract `swagger` member in any
  `Api*ControllerBase` trait; inherit it from `ApiControllerBase`.
- Do not put `apiOperation[...]` anywhere except in the `transformers`
  list of a route declaration (via `operation(theVal)`).
