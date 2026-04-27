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
import org.scalatra.swagger.{ApiResponse, ResponseMessage}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Using

trait ApiRepositoryControllerBase extends ControllerBase {
  self: RepositoryService & ApiGitReferenceControllerBase & RepositoryCreationService & AccountService &
    OwnerAuthenticator & UsersAuthenticator & GroupManagerAuthenticator & ReferrerAuthenticator &
    ReadableUsersAuthenticator & WritableUsersAuthenticator =>

  /**
   * i. List your repositories
   * https://docs.github.com/en/rest/reference/repos#list-repositories-for-the-authenticated-user
   */
  get("/api/v3/user/repos")(usersOnly {
    apiOperation[List[ApiRepository]]("listUserRepositories")
      .summary("List your repositories")
      .description("List repositories for the authenticated user. Includes public and private repositories based on visibility.")
      .queryParam[String]("visibility").description("Filter by visibility (public, private, all)").optional
      .queryParam[String]("affiliation").description("Filter by affiliation (owner, collaborator, organization_member)").optional
      .queryParam[String]("sort").description("Sort by (created, updated, pushed, full_name)").optional
      .queryParam[String]("direction").description("Sort direction (asc, desc)").optional
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(401, "Unauthorized")
      )
    JsonFormat(getVisibleRepositories(context.loginAccount, Option(context.loginAccount.get.userName)).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  })

  /**
   * ii. List user repositories
   * https://docs.github.com/en/rest/reference/repos#list-repositories-for-a-user
   */
  get("/api/v3/users/:userName/repos") {
    apiOperation[List[ApiRepository]]("listUserRepositoriesByUsername")
      .summary("List user repositories")
      .description("List public repositories for a user.")
      .pathParam[String]("userName").description("Username to list repositories for")
      .queryParam[String]("type").description("Filter by type (owner, member)").optional
      .queryParam[String]("sort").description("Sort by (created, updated, pushed, full_name)").optional
      .queryParam[String]("direction").description("Sort direction (asc, desc)").optional
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "User not found")
      )
    JsonFormat(getVisibleRepositories(context.loginAccount, Some(params("userName"))).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  }

  /**
   * iii. List organization repositories
   * https://docs.github.com/en/rest/reference/repos#list-organization-repositories
   */
  get("/api/v3/orgs/:orgName/repos") {
    apiOperation[List[ApiRepository]]("listOrganizationRepositories")
      .summary("List organization repositories")
      .description("List repositories for an organization. Access depends on user's membership and visibility permissions.")
      .pathParam[String]("orgName").description("Organization name")
      .queryParam[String]("type").description("Filter by type (all, public, private, forks, sources, member)").optional
      .queryParam[String]("sort").description("Sort by (created, updated, pushed, full_name)").optional
      .queryParam[String]("direction").description("Sort direction (asc, desc)").optional
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "Organization not found"),
        ResponseMessage(403, "Forbidden")
      )
    JsonFormat(getVisibleRepositories(context.loginAccount, Some(params("orgName"))).map { r =>
      ApiRepository(r, getAccountByUserName(r.owner).get)
    })
  }

  /**
   * iv. List all public repositories
   * https://docs.github.com/en/rest/reference/repos#list-public-repositories
   */
  get("/api/v3/repositories") {
    apiOperation[List[ApiRepository]]("listAllPublicRepositories")
      .summary("List all public repositories")
      .description("List all public repositories on the instance, sorted by creation date.")
      .queryParam[Int]("since").description("Only show repositories with id greater than this value").optional
      .responseMessages(
        ResponseMessage(200, "Success")
      )
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
  post("/api/v3/user/repos")(usersOnly {
    apiOperation[ApiRepository]("createUserRepository")
      .summary("Create a repository for the authenticated user")
      .description("Create a new repository for the authenticated user. Requires authentication.")
      .bodyParam[CreateARepository]("body").description("Repository creation data including name, description, private flag, auto_init")
      .responseMessages(
        ResponseMessage(201, "Repository created"),
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(422, "Repository already exists or invalid data")
      )
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
  post("/api/v3/orgs/:org/repos")(usersOnly {
    apiOperation[ApiRepository]("createOrganizationRepository")
      .summary("Create an organization repository")
      .description("Create a new repository for an organization. Requires authentication and organization membership permissions.")
      .pathParam[String]("org").description("Organization name")
      .bodyParam[CreateARepository]("body").description("Repository creation data including name, description, private flag, auto_init")
      .responseMessages(
        ResponseMessage(201, "Repository created"),
        ResponseMessage(401, "Unauthorized"),
        ResponseMessage(403, "Forbidden - insufficient permissions"),
        ResponseMessage(422, "Repository already exists or invalid data")
      )
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
  get("/api/v3/repos/:owner/:repository")(referrersOnly { repository =>
    apiOperation[ApiRepository]("getRepository")
      .summary("Get a repository")
      .description("Get details of a single repository. Requires read access.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "Repository not found")
      )
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
  get("/api/v3/repos/:owner/:repository/tags")(referrersOnly { repository =>
    apiOperation[List[ApiTag]]("listRepositoryTags")
      .summary("List repository tags")
      .description("List tags for a repository. Returns tag name and commit SHA.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "Repository not found")
      )
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
  get("/api/v3/repos/:owner/:repository/raw/*")(referrersOnly { repository =>
    apiOperation[Array[Byte]]("getRepositoryRawFile")
      .summary("Get raw file content from repository")
      .description("Get raw file content from a repository at a specific commit. Non-GitHub compatible API for Jenkins plugin.")
      .pathParam[String]("owner").description("Repository owner")
      .pathParam[String]("repository").description("Repository name")
      .pathParam[String]("path").description("File path within repository")
      .queryParam[String]("ref").description("Commit SHA, branch, or tag to retrieve file from").optional
      .responseMessages(
        ResponseMessage(200, "Success"),
        ResponseMessage(404, "File or repository not found")
      )
    val (id, path) = repository.splitPath(multiParams("splat").head)
    Using.resource(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
      val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve(id))

      getPathObjectId(git, path, revCommit).map { objectId =>
        responseRawFile(git, objectId, path, repository)
      } getOrElse NotFound()
    }
  })
}
