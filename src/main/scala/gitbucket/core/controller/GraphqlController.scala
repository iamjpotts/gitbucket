package gitbucket.core.controller

import org.json4s._
import org.json4s.jackson.Serialization.write

import scala.util.Try

/**
 * Minimal GraphQL endpoint. `gh auth login` issues exactly one GraphQL query
 * (`viewer { login }`) to learn the authenticated username, and fails the whole login if that
 * call fails — this stub exists to answer that one query. Everything else responds with a
 * GraphQL-shaped "not implemented" error rather than 404, so spec-compliant clients that only
 * check the `errors` field (not the HTTP status) still behave correctly; GitHub's own GraphQL
 * API responds 200 with an `errors` array for execution-time problems.
 */
class GraphqlController extends ControllerBase {

  post("/api/graphql") {
    contentType = formats("json")
    context.loginAccount match {
      case None          => org.scalatra.Unauthorized(write(Map("message" -> "Requires authentication")))
      case Some(account) =>
        val query = Try((parse(request.body) \ "query").extractOpt[String]).toOption.flatten.getOrElse("")
        if (query.contains("viewer")) {
          write(Map("data" -> Map("viewer" -> Map("login" -> account.userName))))
        } else {
          write(
            Map(
              "data" -> null,
              "errors" -> List(
                Map(
                  "message" -> "GitBucket does not yet support this request",
                  "type" -> "NOT_IMPLEMENTED",
                  "path" -> List.empty[String]
                )
              )
            )
          )
        }
    }
  }
}
