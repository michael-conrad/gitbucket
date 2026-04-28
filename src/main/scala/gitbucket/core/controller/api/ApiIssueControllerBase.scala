// SPDX-FileCopyrightText: 2026 Michael Conrad
// SPDX-License-Identifier: Apache-2.0
// Co-authored with AI: OpenCode (ollama-cloud/glm-5.1)

package gitbucket.core.controller.api
import gitbucket.core.api._
import gitbucket.core.controller.ControllerBase
import gitbucket.core.model.{Account, Issue}
import gitbucket.core.service.{AccountService, IssueCreationService, IssuesService, MilestonesService}
import gitbucket.core.service.IssuesService.IssueSearchCondition
import gitbucket.core.service.PullRequestService.PullRequestLimit
import gitbucket.core.util.{ReadableUsersAuthenticator, ReferrerAuthenticator, RepositoryName}
import gitbucket.core.util.Implicits._
import org.scalatra.swagger.{ResponseMessage, Swagger, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax._

trait ApiIssueControllerBase extends ControllerBase {
  self: AccountService & IssuesService & IssueCreationService & MilestonesService & ReadableUsersAuthenticator &
    ReferrerAuthenticator & SwaggerSupport =>

  /*
   * i. List issues
   * https://developer.github.com/v3/issues/#list-issues
   * requested: 1743
   */

  /*
   * ii. List issues for a repository
   * https://developer.github.com/v3/issues/#list-issues-for-a-repository
   */
  val listIssuesOp =
    apiOperation[List[ApiIssue]]("listIssues")
      .summary("List issues for a repository")
      .description("Returns a list of issues for the specified repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name"),
        queryParam[String]("state")
          .description("Filter by state")
          .allowableValues("open", "closed", "all")
          .optional,
        queryParam[String]("milestone").description("Milestone number or *").optional,
        queryParam[String]("labels").description("Comma-separated label names").optional,
        queryParam[String]("sort")
          .description("Sort field")
          .allowableValues("created", "updated", "comments")
          .optional,
        queryParam[String]("direction")
          .description("Sort direction")
          .allowableValues("asc", "desc")
          .optional,
        queryParam[String]("since").description("ISO 8601 timestamp").optional
      )
      .responseMessages(ResponseMessage(404, "Repository not found"))

  get("/repos/:owner/:repository/issues", operation(listIssuesOp))(referrersOnly { repository =>
    val page = IssueSearchCondition.page(request)
    // TODO: more api spec condition
    val condition = IssueSearchCondition(request)
    // val baseOwner = getAccountByUserName(repository.owner).get

    val issues: List[(Issue, Account, List[Account])] =
      searchIssueByApi(
        condition = condition,
        offset = (page - 1) * PullRequestLimit,
        limit = PullRequestLimit,
        repos = repository.owner -> repository.name
      )

    JsonFormat(issues.map { case (issue, issueUser, assigneeUsers) =>
      ApiIssue(
        issue = issue,
        repositoryName = RepositoryName(repository),
        user = ApiUser(issueUser),
        assignees = assigneeUsers.map(ApiUser(_)),
        labels = getIssueLabels(repository.owner, repository.name, issue.issueId)
          .map(ApiLabel(_, RepositoryName(repository))),
        issue.milestoneId.flatMap { getApiMilestone(repository, _) }
      )
    })
  })

  /*
   * iii. Get a single issue
   * https://developer.github.com/v3/issues/#get-a-single-issue
   */
  val getIssueOp =
    apiOperation[ApiIssue]("getIssue")
      .summary("Get a single issue")
      .description("Returns a single issue by its ID for the specified repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name"),
        pathParam[Int]("id").description("Issue number")
      )
      .responseMessages(ResponseMessage(404, "Issue not found"))

  get("/repos/:owner/:repository/issues/:id", operation(getIssueOp))(referrersOnly { repository =>
    (for {
      issueId <- params("id").toIntOpt
      issue <- getIssue(repository.owner, repository.name, issueId.toString)
      assigneeUsers = getIssueAssignees(repository.owner, repository.name, issueId)
      users = getAccountsByUserNames(Set(issue.openedUserName) ++ assigneeUsers.map(_.assigneeUserName), Set())
      openedUser <- users.get(issue.openedUserName)
    } yield {
      JsonFormat(
        ApiIssue(
          issue,
          RepositoryName(repository),
          ApiUser(openedUser),
          assigneeUsers.flatMap(x => users.get(x.assigneeUserName)).map(ApiUser(_)),
          getIssueLabels(repository.owner, repository.name, issue.issueId).map(ApiLabel(_, RepositoryName(repository))),
          issue.milestoneId.flatMap { getApiMilestone(repository, _) }
        )
      )
    }) getOrElse NotFound()
  })

  /*
   * iv. Create an issue
   * https://developer.github.com/v3/issues/#create-an-issue
   */
  val createIssueOp =
    apiOperation[ApiIssue]("createIssue")
      .summary("Create an issue")
      .description("Create a new issue in the specified repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name"),
        bodyParam[CreateAnIssue]("body").description("Issue data")
      )
      .responseMessages(
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(404, "Repository not found")
      )

  post("/repos/:owner/:repository/issues", operation(createIssueOp))(readableUsersOnly { repository =>
    if (isIssueEditable(repository)) { // TODO Should this check is provided by authenticator?
      (for {
        data <- extractFromJsonBody[CreateAnIssue]
        loginAccount <- context.loginAccount
      } yield {
        val milestone = data.milestone.flatMap(getMilestone(repository.owner, repository.name, _))
        val issue = createIssue(
          repository,
          data.title,
          data.body,
          data.assignees,
          milestone.map(_.milestoneId),
          None,
          data.labels,
          loginAccount
        )
        JsonFormat(
          ApiIssue(
            issue,
            RepositoryName(repository),
            ApiUser(loginAccount),
            getIssueAssignees(repository.owner, repository.name, issue.issueId)
              .flatMap(x => getAccountByUserName(x.assigneeUserName, false))
              .map(ApiUser.apply),
            getIssueLabels(repository.owner, repository.name, issue.issueId)
              .map(ApiLabel(_, RepositoryName(repository))),
            issue.milestoneId.flatMap { getApiMilestone(repository, _) }
          )
        )
      }) getOrElse NotFound()
    } else Unauthorized()
  })
  /*
   * v. Edit an issue
   * https://developer.github.com/v3/issues/#edit-an-issue
   */

  /*
   * vi. Lock an issue
   * https://developer.github.com/v3/issues/#lock-an-issue
   */

  /*
   * vii. Unlock an issue
   * https://developer.github.com/v3/issues/#unlock-an-issue
   */
}