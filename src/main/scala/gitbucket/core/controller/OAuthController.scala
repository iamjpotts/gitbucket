package gitbucket.core.controller

import javax.servlet.http.HttpServletRequest

import gitbucket.core.service.OAuthService
import gitbucket.core.util.Implicits._
import gitbucket.core.util.StringUtil
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.scalatra.BadRequest

import scala.util.Try

/**
 * The OAuth 2.0 authorization-code flow, scoped to the well-known GitHub CLI client only
 * (see OAuthService.GH_CLI_CLIENT_ID) — no application registry, since gh's client id and
 * public client secret are embedded in every GitHub-compatible client and don't need to be
 * minted per-installation.
 */
class OAuthController extends ControllerBase with OAuthService {

  get("/login/oauth/authorize") {
    context.loginAccount match {
      case None =>
        redirect("/signin?redirect=" + StringUtil.urlEncode(currentPathWithQuery))
      case Some(_) =>
        val clientId = params.getOrElse("client_id", "")
        val redirectUri = params.getOrElse("redirect_uri", "")
        val state = params.get("state")
        val scope = params.getOrElse("scope", "")

        if (!isKnownClient(clientId)) {
          BadRequest("Unknown client_id")
        } else if (!isLoopbackRedirectUri(redirectUri)) {
          BadRequest("redirect_uri must be a loopback address (127.0.0.1 or localhost) over http")
        } else {
          gitbucket.core.html.oauthAuthorize(clientId, redirectUri, state, scope)
        }
    }
  }

  post("/login/oauth/authorize") {
    context.loginAccount match {
      case None          => Unauthorized()
      case Some(account) =>
        val clientId = params.getOrElse("client_id", "")
        val redirectUri = params.getOrElse("redirect_uri", "")
        val state = params.getOrElse("state", "")
        val scope = params.getOrElse("scope", "")
        val approved = params.get("approve").contains("true")

        if (!isKnownClient(clientId) || !isLoopbackRedirectUri(redirectUri)) {
          BadRequest("Unknown client_id or invalid redirect_uri")
        } else if (approved) {
          val code = issueAuthorizationCode(clientId, account.userName, scope, redirectUri)
          redirect(appendQuery(redirectUri, "code" -> code, "state" -> state))
        } else {
          redirect(appendQuery(redirectUri, "error" -> "access_denied", "state" -> state))
        }
    }
  }

  post("/login/oauth/access_token") {
    val clientId = tokenParam("client_id").getOrElse("")
    val code = tokenParam("code").getOrElse("")
    // gh's own token exchange request (`cli/oauth`'s web-app-flow fallback, verified against
    // the real binary) omits both `redirect_uri` and `grant_type` entirely — GitHub's classic
    // OAuth token endpoint has always treated `grant_type` as implicitly `authorization_code`
    // and doesn't require `redirect_uri` to be echoed back for this flow. Both are still
    // accepted and validated when a client does send them.
    val redirectUri = tokenParam("redirect_uri").getOrElse("")
    val grantTypeOk = tokenParam("grant_type").forall(_ == "authorization_code")
    // gh's client secret is public by design (a native/public client, per RFC 8252) — it is
    // accepted but not required to match anything, since security here comes from the
    // loopback redirect and user consent, not the secret.

    if (!grantTypeOk) {
      tokenErrorResponse("unsupported_grant_type")
    } else if (!isKnownClient(clientId)) {
      tokenErrorResponse("invalid_client")
    } else {
      exchangeCode(clientId, code, redirectUri) match {
        case Some((_, token, scope)) => tokenSuccessResponse(token, scope)
        case None                    => tokenErrorResponse("invalid_grant")
      }
    }
  }

  private def currentPathWithQuery(implicit request: HttpServletRequest): String =
    request.getRequestURI.substring(request.getContextPath.length) +
      Option(request.getQueryString).map("?" + _).getOrElse("")

  private def appendQuery(uri: String, params: (String, String)*): String = {
    val query = params.map { case (k, v) => s"$k=${StringUtil.urlEncode(v)}" }.mkString("&")
    uri + (if (uri.contains("?")) "&" else "?") + query
  }

  /** Reads a token-endpoint field from form params (Scalatra parses
   *  `application/x-www-form-urlencoded` bodies into `params` automatically) or, for a JSON
   *  body, from the parsed request body — the token exchange must accept either encoding. */
  private def tokenParam(name: String)(implicit request: HttpServletRequest): Option[String] =
    params.get(name).orElse {
      if (request.contentType.exists(_.toLowerCase.startsWith("application/json"))) {
        Try(parse(request.body).extract[Map[String, String]]).toOption.flatMap(_.get(name))
      } else {
        None
      }
    }

  private def acceptsJson(implicit request: HttpServletRequest): Boolean =
    Option(request.getHeader("Accept")).exists(_.contains("application/json"))

  private def tokenSuccessResponse(token: String, scope: String) =
    if (acceptsJson) {
      contentType = "application/json"
      write(Map("access_token" -> token, "token_type" -> "bearer", "scope" -> scope))
    } else {
      contentType = "application/x-www-form-urlencoded"
      s"access_token=${StringUtil.urlEncode(token)}&token_type=bearer&scope=${StringUtil.urlEncode(scope)}"
    }

  private def tokenErrorResponse(error: String) = {
    status = 400
    if (acceptsJson) {
      contentType = "application/json"
      write(Map("error" -> error))
    } else {
      contentType = "application/x-www-form-urlencoded"
      s"error=${StringUtil.urlEncode(error)}"
    }
  }
}
