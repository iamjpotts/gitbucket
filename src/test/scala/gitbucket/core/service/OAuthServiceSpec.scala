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
}
