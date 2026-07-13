package gitbucket.core.service

import java.net.URI
import java.util.Date

import gitbucket.core.model.Profile._
import gitbucket.core.model.Profile.profile.blockingApi._
import gitbucket.core.model.Profile.dateColumnType
import gitbucket.core.model.{Account, OAuthAccessToken}

import scala.util.Try

trait OAuthService {

  private val AuthorizationCodeTtlMillis = 10 * 60 * 1000L

  /**
   * @return the raw (plain) authorization code; only its hash is stored.
   */
  def issueAuthorizationCode(clientId: String, userName: String, scopes: String, redirectUri: String)(implicit
    s: Session
  ): String = {
    var code: String = AccessTokenService.makeAccessTokenString
    var hash: String = AccessTokenService.tokenToHash(code)

    while (OAuthAuthorizationCodes.filter(_.codeHash === hash.bind).exists.run) {
      code = AccessTokenService.makeAccessTokenString
      hash = AccessTokenService.tokenToHash(code)
    }

    val expiresAt = new Date(currentDate.getTime + AuthorizationCodeTtlMillis)
    OAuthAuthorizationCodes insert
      gitbucket.core.model.OAuthAuthorizationCode(hash, clientId, userName, scopes, redirectUri, expiresAt)
    code
  }

  /**
   * Validates the code (must exist, match `clientId`, and not be expired), consumes it
   * (single-use), and issues a new access token.
   *
   * `redirectUri`, when non-empty, must match exactly what was passed to
   * `issueAuthorizationCode`. An empty `redirectUri` skips that check — verified against the
   * real `gh` binary, `cli/oauth`'s web-app-flow token exchange omits `redirect_uri` entirely,
   * matching GitHub's own classic OAuth token endpoint, which doesn't require it back either.
   *
   * @return (tokenId, raw access token, granted scopes)
   */
  def exchangeCode(clientId: String, plainCode: String, redirectUri: String)(implicit
    s: Session
  ): Option[(Int, String, String)] = {
    val hash = AccessTokenService.tokenToHash(plainCode)
    val baseFilter = OAuthAuthorizationCodes.filter { c =>
      c.codeHash === hash.bind &&
      c.clientId === clientId.bind &&
      c.expiresAt > currentDate.bind
    }
    val filtered = if (redirectUri.isEmpty) baseFilter else baseFilter.filter(_.redirectUri === redirectUri.bind)
    filtered.firstOption
      .map { code =>
        // Single-use: the code row is deleted before the token is issued.
        OAuthAuthorizationCodes.filter(_.codeHash === hash.bind).delete

        var token: String = AccessTokenService.makeAccessTokenString
        var tokenHash: String = AccessTokenService.tokenToHash(token)
        while (OAuthAccessTokens.filter(_.tokenHash === tokenHash.bind).exists.run) {
          token = AccessTokenService.makeAccessTokenString
          tokenHash = AccessTokenService.tokenToHash(token)
        }

        val newToken =
          OAuthAccessToken(
            tokenHash = tokenHash,
            clientId = code.clientId,
            userName = code.userName,
            scopes = code.scopes,
            createdAt = currentDate
          )
        val tokenId = (OAuthAccessTokens returning OAuthAccessTokens.map(_.tokenId)) insert newToken
        (tokenId, token, code.scopes)
      }
  }

  def validateToken(rawToken: String)(implicit s: Session): Option[Account] =
    Accounts
      .join(OAuthAccessTokens)
      .filter { case (ac, t) =>
        (ac.userName === t.userName) && (t.tokenHash === AccessTokenService.tokenToHash(rawToken).bind) &&
        (ac.removed === false.bind)
      }
      .map { case (ac, t) => ac }
      .firstOption

  def isKnownClient(clientId: String): Boolean = clientId == OAuthService.GH_CLI_CLIENT_ID

  /**
   * RFC 8252 §7.3: native-app OAuth clients (like the well-known gh CLI client) can't
   * pre-register a fixed redirect URI, since they listen on a loopback port chosen at
   * runtime. Exact-match redirect validation is relaxed to any port on `127.0.0.1` or
   * `localhost` over plain `http`.
   */
  def isLoopbackRedirectUri(uri: String): Boolean =
    Try(new URI(uri)).toOption.exists { parsed =>
      parsed.getScheme == "http" && (parsed.getHost == "127.0.0.1" || parsed.getHost == "localhost")
    }
}

object OAuthService extends OAuthService {
  val GH_CLI_CLIENT_ID = "178c6fc778ccc68e1d6a"
}
