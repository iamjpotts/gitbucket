package gitbucket.core.service

import java.util.Date

import gitbucket.core.model.*
import org.scalatest.funsuite.AnyFunSuite
import gitbucket.core.model.Profile.*
import gitbucket.core.model.Profile.profile.blockingApi.*

class OAuthServiceSpec extends AnyFunSuite with ServiceSpecBase {

  private val ClientId = OAuthService.GH_CLI_CLIENT_ID
  private val RedirectUri = "http://127.0.0.1:54321/callback"

  test("issueAuthorizationCode + exchangeCode succeeds with the correct redirectUri, within TTL") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo read:org gist", RedirectUri)

      OAuthService.exchangeCode(ClientId, code, RedirectUri) match {
        case Some((tokenId, token, scopes)) =>
          assert(tokenId != 0)
          assert(scopes == "repo read:org gist")
          assert(OAuthService.validateToken(token).map(_.userName) == Some("root"))
        case None => fail("expected a token")
      }
    }
  }

  test("exchangeCode fails with the wrong redirectUri") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      assert(OAuthService.exchangeCode(ClientId, code, "http://127.0.0.1:1/other") == None)
    }
  }

  test("exchangeCode fails when the code is expired") {
    withTestDB { implicit session =>
      val plainCode = "expired-code"
      val hash = AccessTokenService.tokenToHash(plainCode)
      val pastExpiry = new Date(System.currentTimeMillis() - 1000)
      OAuthAuthorizationCodes insert
        OAuthAuthorizationCode(hash, ClientId, "root", "repo", RedirectUri, pastExpiry)

      assert(OAuthService.exchangeCode(ClientId, plainCode, RedirectUri) == None)
    }
  }

  test("exchangeCode fails the second time for an already-used code (single-use)") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      assert(OAuthService.exchangeCode(ClientId, code, RedirectUri).isDefined)
      assert(OAuthService.exchangeCode(ClientId, code, RedirectUri) == None)
    }
  }

  test("exchangeCode fails for an unknown clientId") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      assert(OAuthService.exchangeCode("some-other-client", code, RedirectUri) == None)
    }
  }

  test("validateToken resolves the owning account for a valid token") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      val Some((_, token, _)) = OAuthService.exchangeCode(ClientId, code, RedirectUri): @unchecked
      assert(OAuthService.validateToken(token).map(_.userName) == Some("root"))
    }
  }

  test("validateToken returns None for a garbage/unknown token") {
    withTestDB { implicit session =>
      assert(OAuthService.validateToken("not-a-real-token") == None)
    }
  }

  test("validateToken does not resolve a removed account") {
    withTestDB { implicit session =>
      val user2 = generateNewAccount("user2")
      val code = OAuthService.issueAuthorizationCode(ClientId, "user2", "repo", RedirectUri)
      val Some((_, token, _)) = OAuthService.exchangeCode(ClientId, code, RedirectUri): @unchecked

      AccountService.updateAccount(user2.copy(isRemoved = true))

      assert(OAuthService.validateToken(token) == None)
    }
  }

  test("isKnownClient recognizes only the well-known gh CLI client id") {
    assert(OAuthService.isKnownClient("178c6fc778ccc68e1d6a"))
    assert(!OAuthService.isKnownClient("anything-else"))
  }

  test("getOAuthAccessTokens returns the issued token for its owner") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo read:org gist", RedirectUri)
      val Some((tokenId, _, scopes)) = OAuthService.exchangeCode(ClientId, code, RedirectUri): @unchecked

      val tokens = OAuthService.getOAuthAccessTokens("root")
      assert(tokens.map(_.tokenId) == List(tokenId))
      assert(tokens.head.clientId == ClientId)
      assert(tokens.head.scopes == scopes)
    }
  }

  test("getOAuthAccessTokens does not return another user's tokens") {
    withTestDB { implicit session =>
      generateNewAccount("user2")
      val rootCode = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      OAuthService.exchangeCode(ClientId, rootCode, RedirectUri)
      val user2Code = OAuthService.issueAuthorizationCode(ClientId, "user2", "gist", RedirectUri)
      val Some((user2TokenId, _, _)) = OAuthService.exchangeCode(ClientId, user2Code, RedirectUri): @unchecked

      val user2Tokens = OAuthService.getOAuthAccessTokens("user2")
      assert(user2Tokens.map(_.tokenId) == List(user2TokenId))
      assert(!OAuthService.getOAuthAccessTokens("root").exists(_.tokenId == user2TokenId))
    }
  }

  test("deleteOAuthAccessToken removes the owner's token") {
    withTestDB { implicit session =>
      val code = OAuthService.issueAuthorizationCode(ClientId, "root", "repo", RedirectUri)
      val Some((tokenId, token, _)) = OAuthService.exchangeCode(ClientId, code, RedirectUri): @unchecked

      OAuthService.deleteOAuthAccessToken("root", tokenId)

      assert(OAuthService.getOAuthAccessTokens("root").isEmpty)
      assert(OAuthService.validateToken(token) == None)
    }
  }

  test("deleteOAuthAccessToken cannot delete another user's token") {
    withTestDB { implicit session =>
      generateNewAccount("user2")
      val code = OAuthService.issueAuthorizationCode(ClientId, "user2", "repo", RedirectUri)
      val Some((tokenId, token, _)) = OAuthService.exchangeCode(ClientId, code, RedirectUri): @unchecked

      OAuthService.deleteOAuthAccessToken("root", tokenId)

      assert(OAuthService.getOAuthAccessTokens("user2").map(_.tokenId) == List(tokenId))
      assert(OAuthService.validateToken(token).map(_.userName) == Some("user2"))
    }
  }
}
