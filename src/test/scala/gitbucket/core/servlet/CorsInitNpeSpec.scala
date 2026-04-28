package gitbucket.core.servlet

import org.json4s._
import org.json4s.jackson.JsonMethods
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import java.net.HttpURLConnection
import java.net.URI

class CorsInitNpeSpec extends AnyFunSuite with BeforeAndAfterAll {

  private def httpGet(url: String): (Int, Option[String]) = {
    var retries = 0
    var lastException: Option[Exception] = None
    while (retries < 30) {
      try {
        val conn = URI.create(url).toURL.openConnection().asInstanceOf[HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(3000)
        conn.setReadTimeout(3000)
        val code = conn.getResponseCode
        val body = if (code < 400) {
          val source = scala.io.Source.fromInputStream(conn.getInputStream)
          try { Some(source.mkString) } finally { source.close() }
        } else {
          val errStream = conn.getErrorStream
          if (errStream != null) {
            val source = scala.io.Source.fromInputStream(errStream)
            try { Some(source.mkString) } finally { source.close() }
          } else None
        }
        return (code, body)
      } catch {
        case e: Exception => lastException = Some(e)
      }
      Thread.sleep(1000)
      retries += 1
    }
    throw lastException.getOrElse(new RuntimeException(s"Failed to connect to $url after 30 retries"))
  }

  test("SC-FIX-1: /api/v3 endpoint should return non-500 response (no CORS NPE)") {
    val (code, body) = httpGet("http://localhost:8080/api/v3")
    assert(code != 500, s"Expected non-500 from /api/v3, got $code with body: ${body.getOrElse("(empty)")}")
  }

  test("SC-FIX-1b: /api/v3/repos/does/not/exist/issues/1 should return 404 not 500 (no CORS NPE)") {
    val (code, body) = httpGet("http://localhost:8080/api/v3/repos/does/not/exist/issues/1")
    assert(code != 500, s"Expected non-500 from issues endpoint, got $code with body: ${body.getOrElse("(empty)")}")
  }

  test("SC-FIX-1c: /api/v3/user should return 401 not 500 (no CORS NPE)") {
    val (code, body) = httpGet("http://localhost:8080/api/v3/user")
    assert(code != 500, s"Expected non-500 from /api/v3/user, got $code with body: ${body.getOrElse("(empty)")}")
  }

  test("SC-FIX-4: swagger.json should still serve populated paths (no CORS regression)") {
    val (code, body) = httpGet("http://localhost:8080/api-docs/swagger.json")
    assert(code == 200, s"Expected 200 from swagger.json, got $code")
    val json = JsonMethods.parse(body.get)
    implicit val formats: Formats = DefaultFormats
    val paths = (json \ "paths").extract[Map[String, JValue]]
    assert(paths.nonEmpty, "swagger.json should have populated paths")
  }

  test("SC-FIX-2: CORS preflight OPTIONS should return non-500") {
    val conn = URI.create("http://localhost:8080/api/v3").toURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setRequestMethod("OPTIONS")
    conn.setRequestProperty("Origin", "http://example.com")
    conn.setRequestProperty("Access-Control-Request-Method", "GET")
    conn.setConnectTimeout(3000)
    conn.setReadTimeout(3000)
    val code = conn.getResponseCode
    assert(code != 500, s"CORS preflight OPTIONS should not return 500, got $code")
  }
}