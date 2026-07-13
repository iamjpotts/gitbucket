package gitbucket.core

import java.net.{URI, URLDecoder}
import java.util.{Arrays => JArrays}

import gitbucket.core.service.OAuthService
import gitbucket.core.util.HttpClientUtil
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Using

/**
 * Exercises `OAuthController`'s HTTP surface directly (plain HTTP; unlike `GhCliLoginTest`,
 * these don't drive the real `gh` binary, so no TLS is needed).
 */
class OAuthControllerTest extends AnyFunSuite {
  implicit val formats: Formats = DefaultFormats

  private val ClientId = OAuthService.GH_CLI_CLIENT_ID
  private val RedirectUri = "http://127.0.0.1:54321/callback"

  private def queryParams(uri: String): Map[String, String] =
    Option(new URI(uri).getRawQuery)
      .map(
        _.split("&")
          .map { pair =>
            val parts = pair.split("=", 2)
            URLDecoder.decode(parts(0), "UTF-8") -> URLDecoder.decode(parts.lift(1).getOrElse(""), "UTF-8")
          }
          .toMap
      )
      .getOrElse(Map.empty)

  private def authorizeUrl(port: Int, clientId: String, redirectUri: String, state: String, scope: String): String =
    s"http://localhost:$port/login/oauth/authorize?client_id=$clientId&redirect_uri=" +
      java.net.URLEncoder.encode(redirectUri, "UTF-8") + s"&state=$state&scope=" +
      java.net.URLEncoder.encode(scope, "UTF-8")

  /** Signs in, drives GET+POST /login/oauth/authorize with approve=true, and returns the
   *  issued authorization code from the resulting redirect. */
  private def obtainCode(server: TestingGitBucketServer, state: String = "xyz"): String = {
    server.withWebSession("root", "root") { httpClient =>
      val approve = new HttpPost(s"http://localhost:${server.port}/login/oauth/authorize")
      approve.setEntity(
        new UrlEncodedFormEntity(
          JArrays.asList(
            new BasicNameValuePair("client_id", ClientId),
            new BasicNameValuePair("redirect_uri", RedirectUri),
            new BasicNameValuePair("state", state),
            new BasicNameValuePair("scope", "repo read:org gist"),
            new BasicNameValuePair("approve", "true")
          )
        )
      )
      val response = httpClient.execute(approve)
      EntityUtils.consume(response.getEntity)
      assert(response.getStatusLine.getStatusCode == 302, "approve should redirect")
      val location = response.getFirstHeader("Location").getValue
      queryParams(location)("code")
    }
  }

  /** Runs the full authorize+consent+exchange flow and returns a fresh, valid OAuth access
   *  token. */
  private def obtainToken(server: TestingGitBucketServer): String = {
    val code = obtainCode(server)
    HttpClientUtil.withHttpClient(None) { httpClient =>
      val exchange = new HttpPost(s"http://localhost:${server.port}/login/oauth/access_token")
      exchange.setHeader("Accept", "application/json")
      exchange.setEntity(
        new UrlEncodedFormEntity(
          JArrays.asList(
            new BasicNameValuePair("client_id", ClientId),
            new BasicNameValuePair("code", code),
            new BasicNameValuePair("redirect_uri", RedirectUri),
            new BasicNameValuePair("grant_type", "authorization_code")
          )
        )
      )
      val response = httpClient.execute(exchange)
      val body = EntityUtils.toString(response.getEntity, "UTF-8")
      assert(response.getStatusLine.getStatusCode == 200, body)
      (parse(body) \ "access_token").extract[String]
    }
  }

  test("GET /login/oauth/authorize with no session redirects to /signin") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      Using.resource(
        HttpClients.custom().setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build()
      ) { httpClient =>
        val get = new HttpGet(authorizeUrl(server.port, ClientId, RedirectUri, "s", "repo"))
        val response = httpClient.execute(get)
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 302)
        assert(response.getFirstHeader("Location").getValue.contains("/signin"))
      }
    }
  }

  test("GET /login/oauth/authorize, signed in, valid params renders the consent form") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val get = new HttpGet(authorizeUrl(server.port, ClientId, RedirectUri, "s", "repo read:org gist"))
        val response = httpClient.execute(get)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200)
        assert(body.contains("oauth-approve-form"))
        assert(body.contains("oauth-deny-form"))
      }
    }
  }

  test("GET /login/oauth/authorize, signed in, unknown client_id is rejected") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val get = new HttpGet(authorizeUrl(server.port, "not-a-known-client", RedirectUri, "s", "repo"))
        val response = httpClient.execute(get)
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 400)
      }
    }
  }

  test("GET /login/oauth/authorize, signed in, non-loopback redirect_uri is rejected") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val get = new HttpGet(authorizeUrl(server.port, ClientId, "https://evil.example.com/", "s", "repo"))
        val response = httpClient.execute(get)
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 400)
      }
    }
  }

  test("POST /login/oauth/authorize approve redirects with a code and the original state echoed back") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val approve = new HttpPost(s"http://localhost:${server.port}/login/oauth/authorize")
        approve.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("client_id", ClientId),
              new BasicNameValuePair("redirect_uri", RedirectUri),
              new BasicNameValuePair("state", "the-original-state"),
              new BasicNameValuePair("scope", "repo"),
              new BasicNameValuePair("approve", "true")
            )
          )
        )
        val response = httpClient.execute(approve)
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 302)

        val location = response.getFirstHeader("Location").getValue
        assert(location.startsWith(RedirectUri))
        val query = queryParams(location)
        assert(query("state") == "the-original-state")
        assert(query("code").nonEmpty)
      }
    }
  }

  test("POST /login/oauth/authorize deny redirects with error=access_denied") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val deny = new HttpPost(s"http://localhost:${server.port}/login/oauth/authorize")
        deny.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("client_id", ClientId),
              new BasicNameValuePair("redirect_uri", RedirectUri),
              new BasicNameValuePair("state", "s"),
              new BasicNameValuePair("scope", "repo"),
              new BasicNameValuePair("approve", "false")
            )
          )
        )
        val response = httpClient.execute(deny)
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 302)

        val query = queryParams(response.getFirstHeader("Location").getValue)
        assert(query("error") == "access_denied")
        assert(query("state") == "s")
      }
    }
  }

  test("POST /login/oauth/access_token with Accept: application/json returns a JSON token response") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val code = obtainCode(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val exchange = new HttpPost(s"http://localhost:${server.port}/login/oauth/access_token")
        exchange.setHeader("Accept", "application/json")
        exchange.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("client_id", ClientId),
              new BasicNameValuePair("code", code),
              new BasicNameValuePair("redirect_uri", RedirectUri),
              new BasicNameValuePair("grant_type", "authorization_code")
            )
          )
        )
        val response = httpClient.execute(exchange)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)

        val json = parse(body)
        assert((json \ "token_type").extract[String] == "bearer")
        assert((json \ "access_token").extract[String].nonEmpty)
        assert((json \ "scope").extract[String] == "repo read:org gist")
      }
    }
  }

  test("POST /login/oauth/access_token with no Accept header returns a form-encoded token response") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val code = obtainCode(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val exchange = new HttpPost(s"http://localhost:${server.port}/login/oauth/access_token")
        exchange.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("client_id", ClientId),
              new BasicNameValuePair("code", code),
              new BasicNameValuePair("redirect_uri", RedirectUri),
              new BasicNameValuePair("grant_type", "authorization_code")
            )
          )
        )
        val response = httpClient.execute(exchange)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)
        assert(body.contains("token_type=bearer"))
        assert(body.contains("access_token="))
      }
    }
  }

  test("POST /login/oauth/access_token with an already-exchanged code fails with an error field") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val code = obtainCode(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        def exchange(): (Int, String) = {
          val post = new HttpPost(s"http://localhost:${server.port}/login/oauth/access_token")
          post.setHeader("Accept", "application/json")
          post.setEntity(
            new UrlEncodedFormEntity(
              JArrays.asList(
                new BasicNameValuePair("client_id", ClientId),
                new BasicNameValuePair("code", code),
                new BasicNameValuePair("redirect_uri", RedirectUri),
                new BasicNameValuePair("grant_type", "authorization_code")
              )
            )
          )
          val response = httpClient.execute(post)
          val body = EntityUtils.toString(response.getEntity, "UTF-8")
          (response.getStatusLine.getStatusCode, body)
        }

        val first = exchange()
        assert(first._1 == 200, first._2)

        val second = exchange()
        assert(second._1 == 400, second._2)
        assert((parse(second._2) \ "error").extract[String].nonEmpty)
      }
    }
  }

  test("GET /api/v3/user with 'Authorization: token <OAuth token>' resolves the account") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val token = obtainToken(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val get = new HttpGet(s"http://localhost:${server.port}/api/v3/user")
        get.setHeader("Authorization", s"token $token")
        val response = httpClient.execute(get)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)
        assert((parse(body) \ "login").extract[String] == "root")
      }
    }
  }

  test("GET /api/v3/user with 'Authorization: bearer <OAuth token>' resolves the account") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val token = obtainToken(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val get = new HttpGet(s"http://localhost:${server.port}/api/v3/user")
        get.setHeader("Authorization", s"bearer $token")
        val response = httpClient.execute(get)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)
        assert((parse(body) \ "login").extract[String] == "root")
      }
    }
  }

  test("GET /api/v3/user with an unknown bearer token is rejected with Bad credentials") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val get = new HttpGet(s"http://localhost:${server.port}/api/v3/user")
        get.setHeader("Authorization", "token not-a-real-token")
        val response = httpClient.execute(get)
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 401, body)
        assert(body.contains("Bad credentials"))
      }
    }
  }

  private def graphqlPost(port: Int, query: String, token: Option[String]): HttpPost = {
    val post = new HttpPost(s"http://localhost:$port/api/graphql")
    token.foreach(t => post.setHeader("Authorization", s"token $t"))
    post.setEntity(new StringEntity(s"""{"query":"${query.replace("\"", "\\\"")}"}""", "UTF-8"))
    post
  }

  test("POST /api/graphql with no Authorization is rejected with 401") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val response = httpClient.execute(graphqlPost(server.port, "query UserCurrent{viewer{login}}", None))
        EntityUtils.consume(response.getEntity)
        assert(response.getStatusLine.getStatusCode == 401)
      }
    }
  }

  test("POST /api/graphql, valid bearer token, a viewer query returns the authenticated login") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val token = obtainToken(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val response = httpClient.execute(graphqlPost(server.port, "query UserCurrent{viewer{login}}", Some(token)))
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)
        assert((parse(body) \ "data" \ "viewer" \ "login").extract[String] == "root")
      }
    }
  }

  test("POST /api/graphql, valid bearer token, an unsupported query returns a GraphQL-shaped error") {
    Using.resource(new TestingGitBucketServer(19996)) { server =>
      val token = obtainToken(server)
      HttpClientUtil.withHttpClient(None) { httpClient =>
        val response =
          httpClient.execute(graphqlPost(server.port, """query{repository(owner:"x",name:"y"){id}}""", Some(token)))
        val body = EntityUtils.toString(response.getEntity, "UTF-8")
        assert(response.getStatusLine.getStatusCode == 200, body)
        val json = parse(body)
        assert(json \ "data" == JNull)
        assert(
          (json \ "errors" \ "message").extract[List[String]] == List("GitBucket does not yet support this request")
        )
      }
    }
  }
}
