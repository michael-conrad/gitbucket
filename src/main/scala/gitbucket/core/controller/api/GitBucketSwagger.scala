// SPDX-FileCopyrightText: 2026 Michael Conrad
// SPDX-License-Identifier: Apache-2.0
// Co-authored with AI: OpenCode (ollama-cloud/kimi-k2.6:cloud)

package gitbucket.core.controller.api

import org.scalatra.swagger.{ApiInfo, ContactInfo, LicenseInfo, Swagger}
import org.slf4j.LoggerFactory

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
) {
  private val logger = LoggerFactory.getLogger(getClass)

  def registerResource(listingPath: String, resourcePath: String): Unit = {
    val registered = doc(listingPath).isDefined
    if (registered) {
      logger.info(s"Swagger resource already registered: $listingPath -> $resourcePath")
    } else {
      logger.info(s"Swagger resource pending registration: $listingPath -> $resourcePath (will register on controller initialization)")
    }
  }
}