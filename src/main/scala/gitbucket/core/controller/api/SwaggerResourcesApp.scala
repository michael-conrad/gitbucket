package gitbucket.core.controller.api

import org.json4s.Formats
import org.scalatra.ScalatraServlet
import org.scalatra.swagger.{JacksonSwaggerBase, Swagger}

class SwaggerResourcesApp(implicit override val swagger: Swagger = GitBucketSwagger)
    extends ScalatraServlet
    with JacksonSwaggerBase {

  override implicit protected def jsonFormats: Formats =
    gitbucket.core.api.JsonFormat.jsonFormats
}