package gitbucket.core.controller.api
import gitbucket.core.api._
import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryCreationService, RepositoryService}
import gitbucket.core.servlet.Database
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util._
import gitbucket.core.util.Implicits._
import gitbucket.core.model.Profile.profile.blockingApi._
import org.eclipse.jgit.api.Git
import org.scalatra.Forbidden
import org.scalatra.swagger.{ResponseMessage, Swagger, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Using

trait ApiRepositoryControllerBase extends ControllerBase {
  self: RepositoryService & ApiGitReferenceControllerBase & RepositoryCreationService & AccountService &
    OwnerAuthenticator & UsersAuthenticator & GroupManagerAuthenticator & ReferrerAuthenticator &
    ReadableUsersAuthenticator & WritableUsersAuthenticator & SwaggerSupport =>

  val listUserReposOp =
    apiOperation[List[ApiRepository]]("listUserRepos")
      .summary("List repositories for the authenticated user")
      .description("Lists repositories that the authenticated user has explicit permission to access")
      .responseMessages(ResponseMessage(401, "Unauthorized"))

  val listReposForUserOp =
    apiOperation[List[ApiRepository]]("listReposForUser")
      .summary("List repositories for a user")
      .description("Lists public repositories for the specified user")
      .parameters(
        pathParam[String]("userName").description("Username")
      )
      .responseMessages(ResponseMessage(404, "User not found"))

  val listReposForOrgOp =
    apiOperation[List[ApiRepository]]("listReposForOrg")
      .summary("List repositories for an organization")
      .description("Lists repositories for the specified organization")
      .parameters(
        pathParam[String]("orgName").description("Organization name")
      )
      .responseMessages(ResponseMessage(404, "Organization not found"))

  val listPublicReposOp =
    apiOperation[List[ApiRepository]]("listPublicRepos")
      .summary("List public repositories")
      .description("Lists all public repositories on the instance")

  val createUserRepoOp =
    apiOperation[ApiRepository]("createUserRepo")
      .summary("Create a repository for the authenticated user")
      .description("Creates a new repository for the authenticated user")
      .parameters(
        bodyParam[CreateARepository]("body").description("Repository creation parameters")
      )
      .responseMessages(
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(422, "Validation failed")
      )

  val createOrgRepoOp =
    apiOperation[ApiRepository]("createOrgRepo")
      .summary("Create an organization repository")
      .description("Creates a new repository in the specified organization")
      .parameters(
        pathParam[String]("org").description("Organization name"),
        bodyParam[CreateARepository]("body").description("Repository creation parameters")
      )
      .responseMessages(
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(403, "Forbidden"),
        ResponseMessage(404, "Organization not found"),
        ResponseMessage(422, "Validation failed")
      )

  val getRepoOp =
    apiOperation[ApiRepository]("getRepo")
      .summary("Get a repository")
      .description("Returns the specified repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name")
      )
      .responseMessages(ResponseMessage(404, "Repository not found"))

  val listRepoTagsOp =
    apiOperation[List[ApiTag]]("listRepoTags")
      .summary("List repository tags")
      .description("Returns a list of tags for the specified repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name")
      )
      .responseMessages(ResponseMessage(404, "Repository not found"))

  val getRawFileOp =
    apiOperation[Unit]("getRawFile")
      .summary("Get raw file content")
      .description("Returns the raw content of a file in a repository")
      .parameters(
        pathParam[String]("owner").description("Repository owner"),
        pathParam[String]("repository").description("Repository name"),
        pathParam[String]("splat").description("Path including git reference and file path")
      )
      .responseMessages(ResponseMessage(404, "File or repository not found"))

  /**
   * i. List your repositories
   * https://docs.github.com/en/rest/reference/repos#list-repositories-for-the-authenticated-user
   */
  get("/user/repos", operation(listUserReposOp))(usersOnly {
    JsonFormat(getVisibleRepositories(context.loginAccount, Option(context.loginAccount.get.userName)).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  })

  /**
   * ii. List user repositories
   * https://docs.github.com/en/rest/reference/repos#list-repositories-for-a-user
   */
  get("/users/:userName/repos", operation(listReposForUserOp)) {
    JsonFormat(getVisibleRepositories(context.loginAccount, Some(params("userName"))).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  }

  /**
   * iii. List organization repositories
   * https://docs.github.com/en/rest/reference/repos#list-organization-repositories
   */
  get("/orgs/:orgName/repos", operation(listReposForOrgOp)) {
    JsonFormat(getVisibleRepositories(context.loginAccount, Some(params("orgName"))).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  }

  /**
   * iv. List all public repositories
   * https://docs.github.com/en/rest/reference/repos#list-public-repositories
   */
  get("/repositories", operation(listPublicReposOp)) {
    JsonFormat(getPublicRepositories().map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  }

  /*
   * v. Create
   * Implemented with two methods (user/orgs)
   */

  /**
   * Create user repository
   * https://docs.github.com/en/rest/reference/repos#create-a-repository-for-the-authenticated-user
   */
  post("/user/repos", operation(createUserRepoOp))(usersOnly {
    val owner = context.loginAccount.get.userName
    (for {
      data <- extractFromJsonBody[CreateARepository] if data.isValid
    } yield {
      LockUtil.lock(s"${owner}/${data.name}") {
        if (getRepository(owner, data.name).isDefined) {
          ApiError(
            "A repository with this name already exists on this account",
            Some("https://developer.github.com/v3/repos/#create")
          )
        } else {
          val f = createRepository(
            context.loginAccount.get,
            owner,
            data.name,
            data.description,
            data.`private`,
            data.auto_init,
            context.settings.defaultBranch
          )
          Await.result(f, Duration.Inf)

          val repository = Database() withTransaction { session =>
            getRepository(owner, data.name)(session).get
          }
          JsonFormat(ApiRepository(repository, ApiUser(getAccountByUserName(owner).get)))
        }
      }
    }) getOrElse NotFound()
  })

  /**
   * Create group repository
   * https://docs.github.com/en/rest/reference/repos#create-an-organization-repository
   */
  post("/orgs/:org/repos", operation(createOrgRepoOp))(usersOnly {
    val groupName = params("org")
    (for {
      data <- extractFromJsonBody[CreateARepository] if data.isValid
    } yield {
      LockUtil.lock(s"${groupName}/${data.name}") {
        if (getRepository(groupName, data.name).isDefined) {
          ApiError(
            "A repository with this name already exists for this group",
            Some("https://developer.github.com/v3/repos/#create")
          )
        } else if (!canCreateRepository(groupName, context.loginAccount.get)) {
          Forbidden()
        } else {
          val f = createRepository(
            context.loginAccount.get,
            groupName,
            data.name,
            data.description,
            data.`private`,
            data.auto_init,
            context.settings.defaultBranch
          )
          Await.result(f, Duration.Inf)
          val repository = Database() withTransaction { session =>
            getRepository(groupName, data.name).get
          }
          JsonFormat(ApiRepository(repository, ApiUser(getAccountByUserName(groupName).get)))
        }
      }
    }) getOrElse NotFound()
  })

  /*
   * vi. Get
   * https://docs.github.com/en/rest/reference/repos#get-a-repository
   */
  get("/repos/:owner/:repository", operation(getRepoOp))(referrersOnly { repository =>
    JsonFormat(ApiRepository(repository, ApiUser(getAccountByUserName(repository.owner).get)))
  })

  /*
   * vii. Edit
   * https://docs.github.com/en/rest/reference/repos#update-a-repository
   */

  /*
   * viii. List all topics for a repository
   * https://docs.github.com/en/rest/reference/repos#get-all-repository-topics
   */

  /*
   * ix. Replace all topics for a repository
   * https://docs.github.com/en/rest/reference/repos#replace-all-repository-topics
   */

  /*
   * x. List contributors
   * https://docs.github.com/en/rest/reference/repos#list-repository-contributors
   */

  /*
   * xi. List languages
   * https://docs.github.com/en/rest/reference/repos#list-repository-languages
   */

  /*
   * xii. List teams
   * https://docs.github.com/en/rest/reference/repos#list-repository-teams
   */

  /*
   * xiii. List repository tags
   * https://docs.github.com/en/rest/reference/repos#list-repository-tags
   */
  get("/repos/:owner/:repository/tags", operation(listRepoTagsOp))(referrersOnly { repository =>
    Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
      JsonFormat(
        repository.tags.map(tagInfo => ApiTag(tagInfo.name, RepositoryName(repository), tagInfo.commitId))
      )
    }
  })

  /*
   * xiv. Delete a repository
   * https://docs.github.com/en/rest/reference/repos#delete-a-repository
   */

  /*
   * xv. Transfer a repository
   * https://docs.github.com/en/rest/reference/repos#transfer-a-repository
   */

  /**
   * non-GitHub compatible API for Jenkins-Plugin
   */
  get("/repos/:owner/:repository/raw/*", operation(getRawFileOp))(referrersOnly { repository =>
    val (id, path) = repository.splitPath(multiParams("splat").head)
    Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
      val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve(id))

      getPathObjectId(git, path, revCommit).map { objectId =>
        responseRawFile(git, objectId, path, repository)
      } getOrElse NotFound()
    }
  })
}