package gitbucket.core.controller

import gitbucket.core.api.*
import gitbucket.core.controller.api.*
import gitbucket.core.service.*
import gitbucket.core.util.Implicits.*
import gitbucket.core.util.*
import gitbucket.core.plugin.PluginRegistry
import org.scalatra.swagger.{Swagger, SwaggerSupport}
import org.slf4j.LoggerFactory

class ApiController
    extends ApiControllerBase
    with ApiGitReferenceControllerBase
    with ApiIssueCommentControllerBase
    with ApiIssueControllerBase
    with ApiIssueLabelControllerBase
    with ApiIssueMilestoneControllerBase
    with ApiOrganizationControllerBase
    with ApiPullRequestControllerBase
    with ApiReleaseControllerBase
    with ApiRepositoryBranchControllerBase
    with ApiRepositoryCollaboratorControllerBase
    with ApiRepositoryCommitControllerBase
    with ApiRepositoryContentsControllerBase
    with ApiRepositoryControllerBase
    with ApiRepositoryStatusControllerBase
    with ApiRepositoryWebhookControllerBase
    with ApiUserControllerBase
    with RepositoryService
    with AccountService
    with ProtectedBranchService
    with IssuesService
    with LabelsService
    with MilestonesService
    with PullRequestService
    with CommitsService
    with CommitStatusService
    with ReleaseService
    with RepositoryCreationService
    with RepositoryCommitFileService
    with IssueCreationService
    with HandleCommentService
    with MergeService
    with WebHookService
    with WebHookPullRequestService
    with WebHookIssueCommentService
    with WebHookPullRequestReviewCommentService
    with WikiService
    with ActivityService
    with PrioritiesService
    with AdminAuthenticator
    with OwnerAuthenticator
    with UsersAuthenticator
    with GroupManagerAuthenticator
    with ReferrerAuthenticator
    with ReadableUsersAuthenticator
    with WritableUsersAuthenticator
    with RequestCache {

  override implicit lazy val swagger: Swagger = GitBucketSwagger
}

trait ApiControllerBase extends ControllerBase with SwaggerSupport {

  private val swaggerLogger = LoggerFactory.getLogger(classOf[ApiControllerBase])

  override implicit lazy val swagger: Swagger = GitBucketSwagger
  override protected def applicationDescription: String = "GitBucket API"

  override def initialize(config: ConfigT): Unit = {
    // super.initialize runs the full Scalatra init chain: sets this.config
    // (fixes CorsSupport NPE), seeds CorsConfigKey, etc. It also routes
    // through SwaggerSupportSyntax.initialize, which throws
    // IllegalStateException under CompositeScalatraFilter because it cannot
    // resolve the servlet registration. That exception is caught and
    // printed to System.err by scalatra-swagger itself — it never
    // propagates. The trace is expected and harmless; /api/v3 is
    // registered manually below. See doc/swagger/swagger.md.
    System.err.println(
      "[gitbucket] expected scalatra-swagger init trace follows — see doc/swagger/swagger.md"
    )
    super.initialize(config)
    System.err.println(
      "[gitbucket] end expected scalatra-swagger init trace"
    )

    try {
      swagger.register(
        "api",
        "/api/v3",
        Some(applicationDescription),
        this,
        swaggerConsumes,
        swaggerProduces,
        swaggerProtocols,
        swaggerAuthorizations
      )
      swaggerLogger.info("Swagger manual registration succeeded for ApiControllerBase")
    } catch {
      case e: Exception =>
        swaggerLogger.warn(s"Swagger manual registration failed: ${e.getMessage}", e)
    }
  }

  /**
   * 404 for non-implemented api paths.
   * These catch-all routes use absolute /api/v3 paths because they are
   * fallback handlers that must match before Scalatra delegates to the
   * servlet container. They are NOT annotated with apiOperation and do
   * not appear in swagger.json. See doc/swagger/swagger.md Section 5.
   */
  get("/api/v3/*") {
    NotFound()
  }
  post("/api/v3/*") {
    NotFound()
  }
  put("/api/v3/*") {
    NotFound()
  }
  delete("/api/v3/*") {
    NotFound()
  }
  patch("/api/v3/*") {
    NotFound()
  }

  val getApiRoot =
    apiOperation[ApiEndPoint]("getApiRoot")
      .summary("Root endpoint")
      .description("Returns basic API information")

  /**
   * https://developer.github.com/v3/#root-endpoint
   * Route literal is relative ("/" not "/api/v3") per doc/swagger/swagger.md
   * Section 5 — scalatra-swagger prepends the resourcePath "/api/v3".
   */
  get("/", operation(getApiRoot)) {
    JsonFormat(ApiEndPoint())
  }

  /**
   * @see https://developer.github.com/v3/rate_limit/#get-your-current-rate-limit-status
   * but not enabled. Route literal is relative per doc/swagger/swagger.md Section 5.
   */
  get("/rate_limit") {
    contentType = formats("json")
    org.scalatra.NotFound(ApiError("Rate limiting is not enabled."))
  }

  /**
   * non-GitHub compatible API for listing plugins.
   * Route literal is relative per doc/swagger/swagger.md Section 5.
   */
  get("/gitbucket/plugins") {
    PluginRegistry().getPlugins().map { ApiPlugin(_) }
  }
}
