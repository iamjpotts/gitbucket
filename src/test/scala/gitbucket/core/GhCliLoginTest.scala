package gitbucket.core

import java.io.{BufferedReader, File, InputStreamReader}
import java.net.{URI, URLDecoder}
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.{Arrays => JArrays}

import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Using

/**
 * Drives the real `gh` CLI through `gh auth login` against a real, running GitBucket
 * instance over real HTTPS (gh categorically refuses plain HTTP for any non-github.com
 * host). `gh` completes the OAuth handshake and obtains a token, and can now use it to
 * authenticate REST calls, but its login flow also depends on one GraphQL call
 * (`viewer { login }`) to learn the username, and there is no `/api/graphql` route yet.
 *
 * Needs `gh` (verified against 2.86.0) and `keytool` (bundled with the JDK) on `PATH`, and
 * `sbt package` to have run first, same as `ApiIntegrationTest`.
 */
class GhCliLoginTest extends AnyFunSuite {

  // gh prints this line (to stdout) when it falls back from the (unimplemented) device flow
  // to the web-app flow, right before it blocks waiting on its own loopback callback server.
  private val AuthorizeUrlPattern = Pattern.compile("Open this URL to continue in your web browser: (\\S+)")

  test("gh auth login completes an OAuth web flow against GitBucket") {
    Using.resource(new TestingGitBucketServer(19998, enableHttps = true)) { server =>
      server.withWebSession("root", "root") { sessionClient =>
        val ghConfigDir = Files.createTempDirectory("gh-config-").toFile
        val hostAndPort = s"localhost:${server.httpsPort}"

        def ghEnv(pb: ProcessBuilder): Unit = {
          val env = pb.environment()
          env.put("GH_HOST", hostAndPort)
          env.put("SSL_CERT_FILE", server.caCertPath.getAbsolutePath)
          env.put("GH_CONFIG_DIR", ghConfigDir.getAbsolutePath)
        }

        val loginProcessBuilder = new ProcessBuilder("gh", "auth", "login", "-p", "https", "-w", "--insecure-storage")
        loginProcessBuilder.redirectErrorStream(true)
        loginProcessBuilder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
        ghEnv(loginProcessBuilder)

        val loginProcess = loginProcessBuilder.start()
        val reader = new BufferedReader(new InputStreamReader(loginProcess.getInputStream))

        var authorizeUrl: Option[String] = None
        var line: String = null
        while (authorizeUrl.isEmpty && { line = reader.readLine(); line != null }) {
          val matcher = AuthorizeUrlPattern.matcher(line)
          if (matcher.find()) {
            authorizeUrl = Some(matcher.group(1))
          }
        }

        // Drain any further gh output on a background thread so it never blocks on a full pipe
        // while we're doing the consent round-trip below.
        val remainingOutput = new StringBuilder
        val drainThread = new Thread(() => {
          try {
            var l: String = null
            while ({ l = reader.readLine(); l != null }) {
              remainingOutput.append(l).append('\n')
            }
          } catch { case _: Exception => () }
        })
        drainThread.setDaemon(true)
        drainThread.start()

        assert(authorizeUrl.isDefined, "gh exited before printing an authorize URL")

        // Stand in for the browser: load the consent screen, then approve it, over the
        // session established by withWebSession.
        val consentResponse = sessionClient.execute(new HttpGet(authorizeUrl.get))
        val consentBody = Option(consentResponse.getEntity).map(EntityUtils.toString(_, "UTF-8")).getOrElse("")
        assert(
          consentResponse.getStatusLine.getStatusCode == 200,
          s"consent screen did not render: HTTP ${consentResponse.getStatusLine.getStatusCode} - $consentBody"
        )

        val authorizeUri = new URI(authorizeUrl.get)
        val query = authorizeUri.getRawQuery
          .split("&")
          .map { pair =>
            val parts = pair.split("=", 2)
            URLDecoder.decode(parts(0), "UTF-8") -> URLDecoder.decode(parts(1), "UTF-8")
          }
          .toMap

        val approve = new HttpPost(s"https://$hostAndPort/login/oauth/authorize")
        approve.setEntity(
          new UrlEncodedFormEntity(
            JArrays.asList(
              new BasicNameValuePair("client_id", query("client_id")),
              new BasicNameValuePair("redirect_uri", query("redirect_uri")),
              new BasicNameValuePair("state", query("state")),
              new BasicNameValuePair("scope", query.getOrElse("scope", "")),
              new BasicNameValuePair("approve", "true")
            )
          )
        )
        val approveResponse = sessionClient.execute(approve)
        EntityUtils.consume(approveResponse.getEntity)
        assert(
          approveResponse.getStatusLine.getStatusCode == 302,
          s"consent approval did not redirect: HTTP ${approveResponse.getStatusLine.getStatusCode}"
        )

        // Follow the redirect to deliver the code to gh's own loopback callback server,
        // letting `gh auth login` proceed.
        val callbackUrl = approveResponse.getFirstHeader("Location").getValue
        Using.resource(org.apache.http.impl.client.HttpClients.custom().build()) { plainClient =>
          val callbackResponse = plainClient.execute(new HttpGet(callbackUrl))
          EntityUtils.consume(callbackResponse.getEntity)
        }

        val exited = loginProcess.waitFor(10, TimeUnit.SECONDS)
        assert(exited, "gh auth login did not exit within 10 seconds")
        assert(loginProcess.exitValue() == 0, s"gh auth login exited with a non-zero status: $remainingOutput")

        // Verify the login actually took: gh should now be able to make an authenticated API
        // call with the token it just obtained. gh's login flow itself already depends on the
        // /api/graphql viewer{login} stub succeeding (it's how gh learns the username to
        // store), so a clean exit above already exercised that; this re-checks post-login use.
        val apiProcessBuilder = new ProcessBuilder("gh", "api", "user")
        apiProcessBuilder.redirectErrorStream(true)
        ghEnv(apiProcessBuilder)
        val apiProcess = apiProcessBuilder.start()
        val apiOutput = new String(apiProcess.getInputStream.readAllBytes(), "UTF-8")
        apiProcess.waitFor(10, TimeUnit.SECONDS)
        assert(apiProcess.exitValue() == 0, s"gh api user failed: $apiOutput")
        assert(apiOutput.contains("\"login\":\"root\""), s"unexpected gh api user output: $apiOutput")
      }
    }
  }
}
