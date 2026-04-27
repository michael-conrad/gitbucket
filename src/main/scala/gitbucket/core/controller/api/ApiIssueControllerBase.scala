package gitbucket.core.controller.api
import gitbucket.core.api._
import gitbucket.core.controller.ControllerBase
import gitbucket.core.model.{Account, Issue}
import gitbucket.core.service.{AccountService, IssueCreationService, IssuesService, MilestonesService}
import gitbucket.core.service.IssuesService.IssueSearchCondition
import gitbucket.core.service.PullRequestService.PullRequestLimit
import gitbucket.core.util.{ReadableUsersAuthenticator, ReferrerAuthenticator, RepositoryName}
import gitbucket.core.util.Implicits._
import org.scalatra.swagger.{ApiResponse, ResponseMessage}

trait ApiIssueControllerBase extends ControllerBase {
  self: AccountService & IssuesService & IssueCreationService & MilestonesService & ReadableUsersAuthenticator &
    ReferrerAuthenticator =>
  /*
   * i. List issues
   * https://developer.github.com/v3/issues/#list-issues
   * requested: 1743
   */

  /*
   * ii. List issues for a repository
   * https://developer.github.com/v3/issues/#list-issues-for-a-repository
   */
  get("/api/v3/repos/:owner/:repository/issues")(referrersOnly { repository =>
    apiOperation[List[ApiIssue]]("listIssuesForRepository")
      .summary("List issues for a repository")
      .description("List all issues for a repository. Supports filtering by state, labels, milestone, and assignee.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .queryParam[String]("state").description("Filter by state (open, closed, all)").optional
      .queryParam[String]("labels").description("Filter by labels").optional
      .queryParam[Int]("page").description("Page number").optional
      .queryParam[Int]("per_page").description("Results per page").optional
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "Repository not found")
      )
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
  get("/api/v3/repos/:owner/:repository/issues/:id")(referrersOnly { repository =>
    apiOperation[ApiIssue]("getIssue")
      .summary("Get a single issue")
      .description("Get details of a single issue by issue ID.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .pathParam[Int]("id").description("Issue ID")
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "Issue not found")
      )
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
  post("/api/v3/repos/:owner/:repository/issues")(readableUsersOnly { repository =>
    apiOperation[ApiIssue]("createIssue")
      .summary("Create an issue")
      .description("Create a new issue in a repository. Requires authentication.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .bodyParam[CreateAnIssue]("body").description("Issue creation data including title, body, assignees, labels, milestone")
      .responseMessages(
        ResponseMessage(201, "Issue created"),
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(404, "Repository not found")
      )
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
