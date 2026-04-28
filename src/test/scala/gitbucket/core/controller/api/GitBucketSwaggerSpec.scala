// SPDX-FileCopyrightText: 2026 Michael Conrad
// SPDX-License-Identifier: Apache-2.0
// Co-authored with AI: OpenCode (ollama-cloud/glm-5.1)

package gitbucket.core.controller.api

import org.scalatest.funsuite.AnyFunSuite

class GitBucketSwaggerSpec extends AnyFunSuite {

  test("swagger apiVersion should not be hardcoded 1.0") {
    assert(GitBucketSwagger.apiVersion != "1.0",
      s"apiVersion should be dynamically resolved, got hardcoded '${GitBucketSwagger.apiVersion}'")
  }

  test("swagger info.termsOfService should be non-empty") {
    assert(GitBucketSwagger.apiInfo.termsOfServiceUrl.nonEmpty,
      "termsOfServiceUrl should contain a non-empty disclaimer string")
  }

  test("swagger info.license.name should be Apache 2.0") {
    assert(GitBucketSwagger.apiInfo.license.name == "Apache 2.0",
      s"Expected license.name 'Apache 2.0', got '${GitBucketSwagger.apiInfo.license.name}'")
  }

  test("swagger info.license.url should be Apache 2.0 URL") {
    assert(GitBucketSwagger.apiInfo.license.url == "https://www.apache.org/licenses/LICENSE-2.0",
      s"Expected license.url 'https://www.apache.org/licenses/LICENSE-2.0', got '${GitBucketSwagger.apiInfo.license.url}'")
  }

  test("swagger info.contact.name should be takezoe") {
    assert(GitBucketSwagger.apiInfo.contact.name == "takezoe",
      s"Expected contact.name 'takezoe', got '${GitBucketSwagger.apiInfo.contact.name}'")
  }

  test("swagger info.contact.url should be gitbucket.github.io") {
    assert(GitBucketSwagger.apiInfo.contact.url == "https://gitbucket.github.io/",
      s"Expected contact.url 'https://gitbucket.github.io/', got '${GitBucketSwagger.apiInfo.contact.url}'")
  }

  test("swagger info.contact.email should be takezoe GitHub URL") {
    assert(GitBucketSwagger.apiInfo.contact.email == "https://github.com/takezoe",
      s"Expected contact.email 'https://github.com/takezoe', got '${GitBucketSwagger.apiInfo.contact.email}'")
  }
}