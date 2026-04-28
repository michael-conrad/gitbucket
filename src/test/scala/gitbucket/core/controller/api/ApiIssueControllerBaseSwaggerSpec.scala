// Co-authored with AI: OpenCode (ollama-cloud/glm-5.1)

package gitbucket.core.controller.api

import org.json4s.jackson.JsonMethods
import org.json4s._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import java.net.HttpURLConnection
import java.net.URI

class ApiIssueControllerBaseSwaggerSpec extends AnyFunSuite with BeforeAndAfterAll {

  private var swaggerJson: JValue = _

  private def fetchSwaggerJson(): JValue = {
    var retries = 0
    var lastException: Option[Exception] = None
    while (retries < 30) {
      try {
        val url = URI.create("http://localhost:8080/api-docs/swagger.json").toURL
        val conn = url.openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(3000)
        conn.setReadTimeout(3000)
        if (conn.getResponseCode == 200) {
          val source = scala.io.Source.fromInputStream(conn.getInputStream)
          try {
            return JsonMethods.parse(source.mkString)
          } finally {
            source.close()
          }
        }
      } catch {
        case e: Exception => lastException = Some(e)
      }
      Thread.sleep(1000)
      retries += 1
    }
    throw lastException.getOrElse(new RuntimeException("Failed to fetch swagger.json after 30 retries"))
  }

  override def beforeAll(): Unit = {
    swaggerJson = fetchSwaggerJson()
  }

  implicit val formats: Formats = DefaultFormats

  private def getOperation(pathPattern: String, method: String): JValue = {
    val allPaths = (swaggerJson \ "paths").extract[Map[String, JValue]]
    val pathKey = allPaths.keys.find(_.contains(pathPattern)).get
    allPaths(pathKey) \ method
  }

  test("swagger.json should contain listIssues GET endpoint") {
    val op = getOperation("/repos/{owner}/{repository}/issues", "get")
    val operationId = (op \ "operationId").extract[String]
    assert(operationId == "listIssues", s"Expected operationId 'listIssues', got '$operationId'")
    val summary = (op \ "summary").extract[String]
    assert(summary.nonEmpty, "listIssues should have a summary")
  }

  test("swagger.json should contain getIssue GET endpoint") {
    val op = getOperation("/repos/{owner}/{repository}/issues/{id}", "get")
    val operationId = (op \ "operationId").extract[String]
    assert(operationId == "getIssue", s"Expected operationId 'getIssue', got '$operationId'")
    val params = (op \ "parameters").extract[List[JValue]]
    val hasId = params.exists(p => (p \ "name").extract[String] == "id")
    assert(hasId, "getIssue should have 'id' param")
  }

  test("swagger.json should contain createIssue POST endpoint") {
    val op = getOperation("/repos/{owner}/{repository}/issues", "post")
    val operationId = (op \ "operationId").extract[String]
    assert(operationId == "createIssue", s"Expected operationId 'createIssue', got '$operationId'")
    val params = (op \ "parameters").extract[List[JValue]]
    val hasBody = params.exists(p => (p \ "name").extract[String] == "body")
    assert(hasBody, "createIssue should have a 'body' parameter")
  }

  test("swagger.json should contain owner and repository path parameters for listIssues") {
    val op = getOperation("/repos/{owner}/{repository}/issues", "get")
    val params = (op \ "parameters").extract[List[JValue]]
    val paramNames = params.map(p => (p \ "name").extract[String]).toSet
    assert(paramNames.contains("owner"), s"listIssues should have 'owner' param, found: $paramNames")
    assert(paramNames.contains("repository"), s"listIssues should have 'repository' param, found: $paramNames")
  }

  test("swagger.json listIssues should have enum constraints") {
    val op = getOperation("/repos/{owner}/{repository}/issues", "get")
    val params = (op \ "parameters").extract[List[JValue]]
    val stateParam = params.find(p => (p \ "name").extract[String] == "state").get
    val enumVals = (stateParam \ "enum").extract[List[String]]
    assert(enumVals.contains("open"), s"state enum should contain 'open', got: $enumVals")
    assert(enumVals.contains("closed"), s"state enum should contain 'closed', got: $enumVals")
    assert(enumVals.contains("all"), s"state enum should contain 'all', got: $enumVals")
  }

  test("swagger.json paths should not have doubled /api/v3 prefix") {
    val allPaths = (swaggerJson \ "paths").extract[Map[String, JValue]]
    val doubled = allPaths.keys.filter(p => p.contains("/api/v3/api/v3/"))
    assert(doubled.isEmpty, s"Paths must not contain doubled /api/v3 prefix, found: $doubled")
  }

  test("swagger.json basePath should not contain jsessionid") {
    val basePath = swaggerJson \ "basePath"
    if (basePath != JNothing) {
      val bp = basePath.extract[String]
      assert(!bp.contains("jsessionid"), s"basePath must not contain jsessionid, got: $bp")
    }
  }

  test("swagger.json should contain ApiIssue model definition") {
    val defs = (swaggerJson \ "definitions").extract[Map[String, JValue]]
    assert(defs.contains("ApiIssue"), s"ApiIssue model should be registered, found: ${defs.keySet}")
  }

  test("swagger.json should contain CreateAnIssue model definition") {
    val defs = (swaggerJson \ "definitions").extract[Map[String, JValue]]
    assert(defs.contains("CreateAnIssue"), s"CreateAnIssue model should be registered, found: ${defs.keySet}")
  }
}