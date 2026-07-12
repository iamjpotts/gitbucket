package gitbucket.core.model

trait OAuthAuthorizationCodeComponent { self: Profile =>
  import profile.api._
  import self._

  lazy val OAuthAuthorizationCodes = TableQuery[OAuthAuthorizationCodes]

  class OAuthAuthorizationCodes(tag: Tag) extends Table[OAuthAuthorizationCode](tag, "OAUTH_AUTHORIZATION_CODE") {
    val codeHash = column[String]("CODE_HASH", O PrimaryKey)
    val clientId = column[String]("CLIENT_ID")
    val userName = column[String]("USER_NAME")
    val scopes = column[String]("SCOPES")
    val redirectUri = column[String]("REDIRECT_URI")
    val expiresAt = column[java.util.Date]("EXPIRES_AT")
    def * = (codeHash, clientId, userName, scopes, redirectUri, expiresAt).mapTo[OAuthAuthorizationCode]
  }
}

case class OAuthAuthorizationCode(
  codeHash: String,
  clientId: String,
  userName: String,
  scopes: String,
  redirectUri: String,
  expiresAt: java.util.Date
)

trait OAuthAccessTokenComponent { self: Profile =>
  import profile.api._
  import self._

  lazy val OAuthAccessTokens = TableQuery[OAuthAccessTokens]

  class OAuthAccessTokens(tag: Tag) extends Table[OAuthAccessToken](tag, "OAUTH_ACCESS_TOKEN") {
    val tokenId = column[Int]("TOKEN_ID", O AutoInc)
    val tokenHash = column[String]("TOKEN_HASH")
    val clientId = column[String]("CLIENT_ID")
    val userName = column[String]("USER_NAME")
    val scopes = column[String]("SCOPES")
    val createdAt = column[java.util.Date]("CREATED_AT")
    def * = (tokenId, tokenHash, clientId, userName, scopes, createdAt).mapTo[OAuthAccessToken]
  }
}

case class OAuthAccessToken(
  tokenId: Int = 0,
  tokenHash: String,
  clientId: String,
  userName: String,
  scopes: String,
  createdAt: java.util.Date
)
