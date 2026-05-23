# Swagger Approach Validation Test — Clean Room Recreation

This document provides complete, self-contained instructions to recreate the standalone Jetty integration test that validates the Swagger integration approach (empty `resourcePath` + absolute route paths + `bathPath` override) without any GitBucket dependency.

## Purpose

The test proves three critical properties:

1. **Empty `resourcePath` prevents double-prefixing**: `swagger.register("api", "", ...)` combined with absolute route paths produces correct `/api/v3/...` paths in `swagger.json` — no `/api/v3/api/v3/...` double-prefixing.
2. **`bathPath` override produces correct `basePath`**: The `SwaggerResourcesApp` override of `bathPath` (Scalatra-Swagger upstream typo for `basePath`) returns `Some("/api/v3")` and this appears as `"basePath": "/api/v3"` in the generated `swagger.json`.
3. **No GitBucket dependency needed**: The entire test runs with Scalatra + Jetty + json4s — no GitBucket code, no mocks, no stub `ServletConfig`/`ServletContext`. The validation is against real runtime behavior.

## What This Does NOT Validate

- `CompositeScalatraFilter` dispatch behavior (that requires GitBucket's full classpath)
- Authentication or authorization
- All 16 API controller routes (only 2 proof-of-concept routes)
- Catch-all 404 handlers (not annotated, not in this test)

## Project Structure

```
swagger-approach-validation/
├── build.sbt
├── project/
│   └── build.properties
└── src/
    ├── main/scala/gitbucket/swaggertest/
    │   ├── GitBucketSwagger.scala
    │   ├── ApiController.scala
    │   ├── GitBucketSwaggerResourcesApp.scala
    │   └── ScalatraBootstrap.scala
    └── test/scala/gitbucket/swaggertest/
        └── JettyIntegrationSpec.scala
```

## Source Files

### build.sbt

```scala
lazy val root = (project in file("."))
  .settings(
    name := "swagger-approach-validation",
    version := "0.1.0",
    scalaVersion := "2.13.18",
    resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",
    libraryDependencies ++= Seq(
      "org.scalatra"               %% "scalatra-javax"               % "3.1.2",
      "org.scalatra"               %% "scalatra-json-javax"          % "3.1.2",
      "org.scalatra"               %% "scalatra-swagger-javax"       % "3.1.2",
      "io.github.json4s"           %% "json4s-jackson"               % "4.1.0",
      "io.github.json4s"           %% "json4s-ext"                   % "4.1.0",
      "javax.servlet"              % "javax.servlet-api"             % "3.1.0" % Provided,
      "org.eclipse.jetty"          % "jetty-webapp"                  % "9.4.57.v20241219" % Test,
      "org.eclipse.jetty"          % "jetty-servlet"                  % "9.4.57.v20241219" % Test,
      "org.scalatest"             %% "scalatest"                      % "3.2.19" % Test,
    ),
  )
```

Key points:
- Uses `scalatra-swagger-javax` 3.1.2 (javax variant for Scala 2.13)
- Jetty test dependencies are `% Test` only
- No dispatch-core or other HTTP client — uses Java's built-in `HttpURLConnection`
- No GitBucket dependency

### project/build.properties

```
sbt.version=1.10.11
```

### GitBucketSwagger.scala

```scala
package gitbucket.swaggertest

import org.scalatra.swagger.{Swagger, ApiInfo, ContactInfo, LicenseInfo}

object GitBucketSwagger extends Swagger(
  Swagger.SpecVersion,
  "3.0.0",
  ApiInfo(
    title             = "GitBucket API",
    description       = "GitBucket REST API",
    termsOfServiceUrl = "",
    contact           = ContactInfo(name = "GitBucket", url = "https://gitbucket.net", email = "gitbucket@gitbucket.net"),
    license           = LicenseInfo(name = "MIT", url = "https://opensource.org/licenses/MIT")
  )
)
```

### ApiController.scala

```scala
package gitbucket.swaggertest

import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{Swagger, SwaggerSupport, SwaggerSupportSyntax}

trait ApiControllerBase extends ScalatraServlet with SwaggerSupport {

  override implicit lazy val swagger: Swagger = GitBucketSwagger

  override def initialize(config: javax.servlet.ServletConfig): Unit = {
    super.initialize(config)
    swagger.register(
      "api",                     // listingPath
      "",                        // resourcePath — EMPTY STRING (per SPEC-FIX #84)
      Some("GitBucket API v3"),  // description
      this,                      // servlet with SwaggerSupport
      List("application/json"),  // consumes
      List("application/json"),  // produces
      List.empty,                // protocols
      List.empty                 // authorizations
    )
  }
}

class ApiController extends ApiControllerBase {

  override protected def applicationDescription: String = "GitBucket API v3"

  val getApiRoot =
    apiOperation[ApiEndPoint]("getApiRoot")
      .summary("GitBucket API root endpoint")
      .description("Returns basic API information")

  get("/api/v3", operation(getApiRoot)) {
    import org.json4s.DefaultFormats
    implicit val formats = DefaultFormats
    org.json4s.Extraction.decompose(ApiEndPoint())
  }

  val getRateLimit =
    apiOperation[ApiRateLimit]("getRateLimit")
      .summary("Rate limit information")
      .description("Returns rate limit status")

  get("/api/v3/rate_limit", operation(getRateLimit)) {
    import org.json4s.DefaultFormats
    implicit val formats = DefaultFormats
    org.json4s.Extraction.decompose(ApiRateLimit())
  }
}

case class ApiEndPoint(rateLimitUrl: Option[String] = None)
object ApiEndPoint {
  def apply(): ApiEndPoint = new ApiEndPoint(rateLimitUrl = Some("/api/v3/rate_limit"))
}

case class ApiRateLimit(limit: Option[Int] = None, remaining: Option[Int] = None)
object ApiRateLimit {
  def apply(): ApiRateLimit = new ApiRateLimit(limit = Some(5000), remaining = Some(4999))
}
```

Note: This test project uses val-bound pattern (`operation(getApiRoot)`) for its two routes. This is acceptable in the validation project because it is testing wire-up, not annotation style. The main `doc/swagger/swagger.md` mandates inline pattern for new annotations in GitBucket itself.

Key design decisions validated by this test:
- `swagger.register("api", "", ...)` — **empty `resourcePath`** (not `"/api/v3"`)
- Route path literals are **absolute**: `"/api/v3"`, `"/api/v3/rate_limit"` (unchanged from existing code)
- `SwaggerResourcesApp` overrides `bathPath` (Scalatra-Swagger typo, NOT `basePath`)

### GitBucketSwaggerResourcesApp.scala

```scala
package gitbucket.swaggertest

import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{SwaggerBase, Swagger}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.CorsSupport
import org.json4s.{DefaultFormats, Formats}

class GitBucketSwaggerResourcesApp(val swagger: Swagger)
  extends ScalatraServlet with JacksonJsonSupport with CorsSupport with SwaggerBase {

  override implicit protected def jsonFormats: Formats = DefaultFormats

  // CRITICAL: "bathPath" is a TYPO in Scalatra-Swagger upstream — NOT "basePath"
  // We MUST override the typo name. See SwaggerBase.scala source.
  override protected def bathPath: Option[String] = Some("/api/v3")
}
```

### ScalatraBootstrap.scala

```scala
package gitbucket.swaggertest

import org.scalatra.LifeCycle
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    context.mount(new ApiController, "/*")
    context.mount(new GitBucketSwaggerResourcesApp(GitBucketSwagger), "/api-docs/*")
  }
  override def destroy(context: ServletContext): Unit = {}
}
```

### JettyIntegrationSpec.scala

```scala
package gitbucket.swaggertest

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.{HttpURLConnection, URL}
import scala.util.Using

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}

class JettyIntegrationSpec extends AnyFunSuite with BeforeAndAfterAll {

  implicit val formats: Formats = DefaultFormats

  private var server: Server = _
  private var port: Int = _

  private def httpGet(urlStr: String): (Int, String) = {
    val url = new URL(urlStr)
    val conn = url.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.setRequestProperty("Accept", "application/json")
    conn.setConnectTimeout(5000)
    conn.setReadTimeout(5000)
    try {
      val code = conn.getResponseCode
      val is = if (code < 400) conn.getInputStream else conn.getErrorStream
      val content = Using.resource(is) { stream =>
        val reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))
        val sb = new StringBuilder
        var line: String = null
        while ({ line = reader.readLine(); line != null }) { sb.append(line) }
        sb.toString
      }
      (code, content)
    } finally { conn.disconnect() }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    val socket = new java.net.ServerSocket(0)
    port = socket.getLocalPort
    socket.close()
    server = new Server(port)
    val handler = new ServletContextHandler(ServletContextHandler.SESSIONS)
    handler.setContextPath("/")
    handler.addServlet(new ServletHolder(new ApiController), "/*")
    handler.addServlet(new ServletHolder(new GitBucketSwaggerResourcesApp(GitBucketSwagger)), "/api-docs/*")
    server.setHandler(handler)
    server.start()
    var attempts = 0
    var ready = false
    while (!ready && attempts < 20) {
      try { val (code, _) = httpGet(s"http://localhost:$port/api/v3"); if (code == 200) ready = true }
      catch { case _: Exception => Thread.sleep(500) }
      if (!ready) attempts += 1
    }
    if (!ready) throw new RuntimeException(s"Server not ready on port $port")
  }

  override def afterAll(): Unit = {
    try { server.stop() } catch { case _: Exception => }
    super.afterAll()
  }

  test("SC-1: Root API endpoint responds with 200 at /api/v3") {
    val (code, body) = httpGet(s"http://localhost:$port/api/v3")
    assert(code == 200)
    assert(body.contains("rateLimitUrl"))
  }

  test("SC-2: Rate limit endpoint responds with 200 at /api/v3/rate_limit") {
    val (code, body) = httpGet(s"http://localhost:$port/api/v3/rate_limit")
    assert(code == 200)
    assert(body.contains("limit"))
  }

  test("SC-3: swagger.json accessible at /api-docs/swagger.json") {
    val (code, body) = httpGet(s"http://localhost:$port/api-docs/swagger.json")
    assert(code == 200)
    val json = parse(body)
    assert((json \\ "swagger").extractOpt[String].contains("2.0"))
    assert((json \\ "info" \\ "title").extractOpt[String].contains("GitBucket API"))
  }

  test("SC-4: basePath is '/api/v3'") {
    val (code, body) = httpGet(s"http://localhost:$port/api-docs/swagger.json")
    assert(code == 200)
    val json = parse(body)
    val basePath = (json \\ "basePath").extractOpt[String]
    assert(basePath.contains("/api/v3"))
  }

  test("SC-5: No '/api/v3/api/v3' double-prefix in paths") {
    val (code, body) = httpGet(s"http://localhost:$port/api-docs/swagger.json")
    assert(code == 200)
    val json = parse(body)
    val paths = (json \\ "paths").asInstanceOf[JObject].obj.map { case (k, _) => k }
    paths.foreach { path =>
      assert(!path.contains("/api/v3/api/v3"), s"Found double-prefixed path: $path")
    }
    assert(!body.contains("/api/v3/api/v3"))
  }

  test("SC-6: Paths contain '/api/v3' prefix (single)") {
    val (code, body) = httpGet(s"http://localhost:$port/api-docs/swagger.json")
    assert(code == 200)
    val json = parse(body)
    val paths = (json \\ "paths").asInstanceOf[JObject].obj.map { case (k, _) => k }
    assert(paths.exists(_.contains("/api/v3")))
    paths.foreach { path =>
      assert(path.startsWith("/api/v3"), s"Path '$path' should start with /api/v3")
    }
  }

  test("SC-7: Paths are absolute (starting with /api/v3)") {
    val (code, body) = httpGet(s"http://localhost:$port/api-docs/swagger.json")
    assert(code == 200)
    val json = parse(body)
    val paths = (json \\ "paths").asInstanceOf[JObject].obj.map { case (k, _) => k }
    paths.foreach { path =>
      assert(path.startsWith("/api/v3"), s"Path '$path' missing /api/v3 prefix")
    }
  }
}
```

## Running the Tests

```bash
cd swagger-approach-validation
sbt test
```

Expected output: `Tests: succeeded 7, failed 0, canceled 0, ignored 0, pending 0`

All tests are behavioral — they spin up a Jetty server, send HTTP requests, and assert on live responses. If `sbt test` cannot execute (server fails to start, Jetty unavailable), the verdict is FAIL — never substitute structural evidence (e.g., "the source files exist" or "the code compiles") for behavioral evidence. The classification question for each test is: "does this test verify runtime behavior?" The answer is YES for all seven SCs — they spin up a Jetty server and assert on live HTTP responses.

## Success Criteria

| SC | Test | Evidence Type | Expected |
|----|------|---------------|----------|
| SC-1 | Root API endpoint responds with 200 at `/api/v3` | `behavioral` | `200`, body contains `rateLimitUrl` |
| SC-2 | Rate limit endpoint responds with 200 at `/api/v3/rate_limit` | `behavioral` | `200`, body contains `limit` |
| SC-3 | `swagger.json` accessible at `/api-docs/swagger.json` with valid Swagger 2.0 | `behavioral` | `200`, `"swagger":"2.0"`, `"title":"GitBucket API"` |
| SC-4 | `basePath` is `/api/v3` (from `bathPath` override) | `behavioral` | `"basePath":"/api/v3"` |
| SC-5 | No double-prefixing — no `/api/v3/api/v3` in any path | `behavioral` | `0` double-prefixed paths |
| SC-6 | Paths contain `/api/v3` prefix (single) | `behavioral` | all paths start with `/api/v3` |
| SC-7 | Paths are absolute (starting with `/api/v3`) | `behavioral` | all paths start with `/api/v3` |

All SCs are classified `behavioral` because they verify runtime behavior (HTTP responses from a running Jetty server). Confirming that source files compile is `structural` evidence and is insufficient — the `sbt test` execution that produces the `Tests: succeeded 7` result is the required `behavioral` evidence.