package gitbucket.core

import java.util.{Arrays => JArrays}

import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpPost
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Using

/**
 * `"login"` is reserved (ControllerBase.allReservedNames) so it can't collide with the new
 * OAuth provider routes under `/login`. This exercises that rejection the same way
 * `"signin"` (a name reserved from the start) has always been rejected, plus a positive
 * control confirming a non-reserved name is still accepted.
 */
class ReservedNameTest extends AnyFunSuite {

  test("admin cannot create a new account named 'login'") {
    Using.resource(new TestingGitBucketServer(19997)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val post = new HttpPost(s"http://localhost:${server.port}/admin/users/_newuser")
        post.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("userName", "login"),
              new BasicNameValuePair("password", "password"),
              new BasicNameValuePair("fullName", "Login"),
              new BasicNameValuePair("mailAddress", "login@example.com"),
              new BasicNameValuePair("isAdmin", "false")
            )
          )
        )
        val response = httpClient.execute(post)
        EntityUtils.consume(response.getEntity)

        // Scalatra-forms rejects with a bare 400 (no custom error handler is wired for this
        // route) rather than re-rendering the form with an inline message, so this is the
        // observable "the constraint fired" signal here — matches what "signin" already does.
        assert(
          response.getStatusLine.getStatusCode == 400,
          s"expected the reserved-name constraint to reject with 400, got HTTP ${response.getStatusLine.getStatusCode}"
        )
      }
    }
  }

  test("admin cannot create a new account named 'signin', rejected the same way as 'login'") {
    Using.resource(new TestingGitBucketServer(19997)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val post = new HttpPost(s"http://localhost:${server.port}/admin/users/_newuser")
        post.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("userName", "signin"),
              new BasicNameValuePair("password", "password"),
              new BasicNameValuePair("fullName", "Signin"),
              new BasicNameValuePair("mailAddress", "signin@example.com"),
              new BasicNameValuePair("isAdmin", "false")
            )
          )
        )
        val response = httpClient.execute(post)
        EntityUtils.consume(response.getEntity)

        assert(
          response.getStatusLine.getStatusCode == 400,
          s"expected the reserved-name constraint to reject with 400, got HTTP ${response.getStatusLine.getStatusCode}"
        )
      }
    }
  }

  test("admin can create a new account with a non-reserved name (positive control)") {
    Using.resource(new TestingGitBucketServer(19997)) { server =>
      server.withWebSession("root", "root") { httpClient =>
        val post = new HttpPost(s"http://localhost:${server.port}/admin/users/_newuser")
        post.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("userName", "not-reserved-user"),
              new BasicNameValuePair("password", "password"),
              new BasicNameValuePair("fullName", "Not Reserved"),
              new BasicNameValuePair("mailAddress", "not-reserved-user@example.com"),
              new BasicNameValuePair("isAdmin", "false")
            )
          )
        )
        val response = httpClient.execute(post)
        EntityUtils.consume(response.getEntity)

        // Confirms the 400s above are actually caused by the reserved-name constraint, not
        // some unrelated breakage in the endpoint or form.
        assert(
          response.getStatusLine.getStatusCode == 302,
          s"expected a non-reserved user name to be accepted (302 redirect), got HTTP ${response.getStatusLine.getStatusCode}"
        )
      }
    }
  }
}
