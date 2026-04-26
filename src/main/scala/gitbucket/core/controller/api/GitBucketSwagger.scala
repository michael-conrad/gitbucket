package gitbucket.core.controller.api

import org.scalatra.swagger.{ApiInfo, ContactInfo, LicenseInfo, Swagger}

object GitBucketSwagger extends Swagger(
  swaggerVersion = "2.0",
  apiVersion = "1.0",
  apiInfo = ApiInfo(
    title = "GitBucket API",
    description = "GitBucket REST API documentation",
    termsOfServiceUrl = "",
    contact = ContactInfo("", "", ""),
    license = LicenseInfo("", "")
  )
)