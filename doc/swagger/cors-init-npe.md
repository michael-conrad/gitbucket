## Fix: 500 / NPE from CORS filter on every `/api/v3/*` request under sbt local dev

Scope: this document is **only** about the runtime 500 caused by a null
servlet-context lookup in `org.scalatra.CorsSupport`. Other concerns
(annotations, doubled paths, `;jsessionid=`, `SwaggerResourcesApp`
wiring) are covered by sibling docs in this folder.

If you have local edits that already implement an equivalent of the
change described below, **do not discard them**. Verify against this
doc instead of rewriting.

---

### Symptom

- Local dev started via `./sbt ~container:start`.
- Any request to `/api/v3/...` returns HTTP 500.
- Log shows:

  ```
  java.lang.NullPointerException: Cannot invoke "Object.getClass()" because "qual1" is null
      at org.scalatra.servlet.ServletBase$$anon$1.context(ServletBase.scala:25)
      at org.scalatra.ScalatraBase.servletContext(ScalatraBase.scala:123)
      at org.scalatra.CorsSupport.corsConfig(CorsSupport.scala:150)
      at org.scalatra.CorsSupport.handle(CorsSupport.scala:226)
      ...
  ```

### Root cause

The null is **`config` itself**, not a missing `CorsConfigKey`
attribute. `ScalatraBase.config` is assigned exclusively in
`ScalatraBase.initialize` (line 585 of `ScalatraBase.scala`).

`ApiControllerBase.initialize` previously skipped `super.initialize(config)`
to avoid an upstream stack trace from
`org.scalatra.swagger.SwaggerSupportSyntax.initialize` (the `throwAFit`
"I can't work out which servlet registration this is" path that fails
under `CompositeScalatraFilter`). Skipping `super` left `config = null`,
so `servletContext` returned null, so every `/api/v3/*` request NPEd in
`CorsSupport.corsConfig`.

The upstream `throwAFit` is not actually a bug in our code path:
`SwaggerSupportSyntax.initialize` already wraps the failure in
`try { ... } catch { case e: Throwable => e.printStackTrace() }`. The
exception never propagates — only the stack trace is printed to
`System.err`. We need `super.initialize(config)` to run so `config`
gets set and `CorsSupport.initialize` seeds `CorsConfigKey`. The
trace is the price.

### The fix

Edit `src/main/scala/gitbucket/core/controller/ApiController.scala`.
In `ApiControllerBase.initialize`, **call `super.initialize(config)`**
and bracket it with two `System.err.println` lines so anyone reading
the boot log knows the trace between them is expected and harmless.

```scala
override def initialize(config: ConfigT): Unit = {
  // The next line will be followed by an IllegalStateException stack trace
  // from scalatra-swagger ("I can't work out which servlet registration
  // this is"). Expected and harmless: GitBucket dispatches via
  // CompositeScalatraFilter, so scalatra-swagger's auto-discovery cannot
  // resolve ApiController's mount path and prints+swallows the error.
  // We register /api/v3 manually below. See doc/swagger/cors-init-npe.md.
  System.err.println(
    "[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/cors-init-npe.md"
  )
  super.initialize(config)
  System.err.println(
    "[gitbucket] end expected scalatra-swagger init trace"
  )

  swagger.register(
    "api", "/api/v3", Some(applicationDescription),
    this, swaggerConsumes, swaggerProduces, swaggerProtocols, swaggerAuthorizations
  )
}
```

Calling `super.initialize(config)` causes the full Scalatra init chain
to run:

1. `ScalatraBase.initialize` — sets `this.config = config` (fixes the NPE)
   and seeds `CookieOptionsKey`.
2. `CorsSupport.initialize` — seeds `CorsConfigKey` via
   `getOrElseUpdate` (no double-seeding risk).
3. `SwaggerSupportSyntax.initialize` — runs the registration-discovery
   block, which fails under `CompositeScalatraFilter`, prints the trace
   to `System.err`, and continues. Harmless.

The manual `swagger.register(...)` call after `super.initialize` is the
supported registration path for `/api/v3` and stays unchanged.

### Boot output

```
[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/cors-init-npe.md
java.lang.IllegalStateException: I can't work out which servlet registration this is.
    at org.scalatra.swagger.SwaggerSupportSyntax.throwAFit(SwaggerSupport.scala:336)
    ... (rest of upstream trace)
[gitbucket] end expected scalatra-swagger init trace
[INFO] g.c.c.ApiControllerBase - Swagger manual registration succeeded for ApiControllerBase
```

The trace is fully visible (so any future change in upstream behavior
is still diagnosable) but unambiguously framed as expected.

### Bootstrap seed (now redundant)

A previous revision of this doc prescribed seeding
`CorsSupport.CorsConfigKey` directly from `ScalatraBootstrap.init`.
With the fix above, `super.initialize(config)` invokes
`CorsSupport.initialize`, which seeds the key naturally. The bootstrap
seed is therefore redundant.

You may either:

- **Remove** the seed block from `ScalatraBootstrap.init` (preferred —
  cleaner, single source of truth), or
- **Leave it** as belt-and-suspenders. `CorsSupport.initialize` uses
  `getOrElseUpdate`, so a pre-seeded key is left alone. No correctness
  risk either way.

If you remove it, also remove the `import org.scalatra.CorsSupport` if
nothing else in the file references it.

### Anti-patterns — do not do these

1. **Do not skip `super.initialize(config)`.** That is what produced the
   NPE in the first place. `config` must be set, and only the super
   chain sets it.
2. **Do not redirect, suppress, or filter `System.err`** to hide the
   `throwAFit` trace. The bracket-banner above is the supported
   approach: the trace stays visible, framed by context. Earlier
   revisions of this doc proposed `System.setErr(nullStream)` —
   superseded.
3. **Do not hand-replicate the Scalatra init chain** in
   `ApiControllerBase.initialize` (e.g. assigning `this.config = config`
   manually, copying `CorsSupport.initialize`'s body inline). It works
   but is brittle to upstream changes and was rejected in favor of
   calling `super.initialize`.
4. **Do not "just catch the NPE"** in `CorsSupport`, in a wrapper
   filter, or anywhere else. The fix is to make `super.initialize` run.
5. **Do not edit `tmp/scalatra-core-sources/...` or
   `tmp/scalatra-sources/...`.** Those are extracted source dumps for
   reference only. They are not on the compile path.
6. **Do not remove `with SwaggerSupport`** from `ApiControllerBase` or
   the `& SwaggerSupport` self-type from any annotated trait. The
   `apiOperation` / `pathParam` / `bodyParam` / `operation(...)` DSL
   used by every annotated route comes from `SwaggerSupport`.
7. **Do not remove the manual `swagger.register(...)` call.** Stock
   auto-discovery cannot resolve the controller's mount path through
   `CompositeScalatraFilter`; manual registration is the supported path.
8. **Do not edit any `Api*ControllerBase` trait** (e.g.
   `ApiIssueControllerBase.scala`) as part of this fix. Annotation work
   is governed by `annotate-ApiIssueControllerBase.md`.

### Verification

The dev runs the persistent loop:

```bash
while [ 1 ]; do
  echo; echo; echo "===";
  ./sbt ~container:start 2>&1 | tee ./tmp/sbt-output.log;
  echo; echo "*"; sleep 2;
done
```

Two reload modes are available:

- **Auto reload (default).** Save the file. `~container:start`
  recompiles and reloads Jetty in-place. Verify with non-blocking
  commands below.
- **Full reload.** Kill the inner `sbt` process so the outer loop
  relaunches it cold. Then verify.

Verification steps. **Use only non-blocking commands.** Do not run
`tail -f`, `less`, `watch`, or any command that does not return on its
own.

1. After saving, wait for the recompile to settle, then read a bounded
   log tail:

   ```bash
   sleep 15 && tail -n 300 ./tmp/sbt-output.log
   ```

   You should see, in order:

   - `[gitbucket] expected scalatra-swagger init trace follows ...`
   - The `IllegalStateException: I can't work out which servlet registration this is.`
     stack trace from `org.scalatra.swagger.SwaggerSupportSyntax.initialize`.
   - `[gitbucket] end expected scalatra-swagger init trace`
   - `Swagger manual registration succeeded for ApiControllerBase`
   - **No** `NullPointerException` originating in `org.scalatra.CorsSupport`
     anywhere in the log.

2. Confirm the 500 is gone on a real API endpoint:

   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" \
     http://localhost:8080/api/v3/repos/does/not/exist/issues/1
   ```

   Expected: `404` (or `401` — anything that is **not 500**). A `500`
   means the fix did not take effect; re-check that
   `super.initialize(config)` is being called and that the file saved.

3. Confirm `swagger.json` still serves:

   ```bash
   curl -s http://localhost:8080/api-docs/swagger.json \
     | jq '{basePath, paths: (.paths|keys)}'
   ```

   Expected: a populated `paths` array. If `paths` is empty, that is a
   **separate** issue (annotation registration), not the CORS init bug
   this doc covers. Stop here and escalate; do not edit annotations
   chasing it.

### Out of scope for this doc

- `SwaggerResourcesApp.bathPath` override — `swagger-endpoint-jsessionid.md`.
- Stripping `/api/v3` from route literals — `strip-api-v3-prefix.md`.
- `apiOperation` annotations on routes — `annotate-ApiIssueControllerBase.md`.
- Any change to `CompositeScalatraFilter`, `SwaggerResourcesApp`,
  `GitBucketSwagger`, `web.xml`, or any `Api*ControllerBase` trait.

The only files touched by this fix are
`src/main/scala/gitbucket/core/controller/ApiController.scala`
(add bracket banners around `super.initialize(config)`) and
optionally `src/main/scala/ScalatraBootstrap.scala` (remove the now-
redundant `CorsConfigKey` seed block).
