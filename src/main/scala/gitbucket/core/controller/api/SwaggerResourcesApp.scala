// SPDX-FileCopyrightText: 2026 Michael Conrad
// SPDX-License-Identifier: Apache-2.0
// Co-authored with AI: OpenCode (ollama-cloud/kimi-k2.6:cloud), OpenCode (ollama-cloud/glm-5.1)

package gitbucket.core.controller.api

import org.json4s.Formats
import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{JacksonSwaggerBase, Swagger}

class SwaggerResourcesApp(implicit override val swagger: Swagger = GitBucketSwagger)
    extends ScalatraServlet
    with JacksonSwaggerBase {

  override implicit protected def jsonFormats: Formats =
    gitbucket.core.api.JsonFormat.jsonFormats

  // Strip any path parameters (e.g. ;jsessionid=...) from the
  // computed basePath so swagger.json does not leak session ids
  // when the docs endpoint is hit without a cookie. Scoped to
  // this servlet only — does not affect session tracking
  // anywhere else in the application.
  override protected def bathPath: Option[String] = {
    super.bathPath.map(stripPathParameters).filter(_.nonEmpty)
  }

  private def stripPathParameters(path: String): String = {
    val semi = path.indexOf(';')
    if (semi >= 0) path.substring(0, semi) else path
  }
}