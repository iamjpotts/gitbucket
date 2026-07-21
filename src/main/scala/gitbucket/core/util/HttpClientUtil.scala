package gitbucket.core.util

import java.net.{InetAddress, URI}

import gitbucket.core.service.SystemSettingsService
import org.apache.commons.net.util.SubnetUtils
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.{BasicCredentialsProvider, CloseableHttpClient, HttpClientBuilder}
import scala.util.{Try, Using}

object HttpClientUtil {

  def withHttpClient[T](proxy: Option[SystemSettingsService.Proxy])(f: CloseableHttpClient => T): T = {
    val builder = HttpClientBuilder.create.useSystemProperties

    proxy.foreach { proxy =>
      builder.setProxy(new HttpHost(proxy.host, proxy.port))

      for (user <- proxy.user; password <- proxy.password) {
        val credential = new BasicCredentialsProvider()
        credential.setCredentials(
          new AuthScope(proxy.host, proxy.port),
          new UsernamePasswordCredentials(user, password)
        )
        builder.setDefaultCredentialsProvider(credential)
      }
    }

    Using.resource(builder.build())(f)
  }

  def isPrivateAddress(address: String): Boolean = {
    val ipAddress = InetAddress.getByName(address)
    ipAddress.isSiteLocalAddress || ipAddress.isLinkLocalAddress || ipAddress.isLoopbackAddress
  }

  def inIpRange(ipRange: String, ipAddress: String): Boolean = {
    if (ipRange.contains('/')) {
      val utils = new SubnetUtils(ipRange)
      utils.setInclusiveHostCount(true)
      utils.getInfo.isInRange(ipAddress)
    } else {
      ipRange == ipAddress
    }
  }

  /**
   * RFC 8252 §7.3: native-app OAuth clients (like the well-known gh CLI client) can't
   * pre-register a fixed redirect URI, since they listen on a loopback port chosen at
   * runtime. Exact-match redirect validation is relaxed to any port on `127.0.0.1`,
   * `localhost`, or an IPv6 loopback address, over plain `http`.
   */
  def isLoopbackRedirectUri(uri: String): Boolean =
    Try(new URI(uri)).toOption.exists { parsed =>
      val host = parsed.getHost
      parsed.getScheme == "http" && host != null &&
      (host == "127.0.0.1" || host == "localhost" || isIPv6Loopback(host))
    }

  private def isIPv6Loopback(host: String): Boolean =
    host.startsWith("[") && host.endsWith("]") &&
      Try(InetAddress.getByName(host.substring(1, host.length - 1)).isLoopbackAddress).getOrElse(false)

  /**
   * Used to decide whether to suggest a host as a `gh auth login --hostname` hint: only
   * hostnames that a `gh` running on a different machine could plausibly resolve and reach,
   * so `localhost`, bare IPv4 addresses, and IPv6 addresses (which contain `:`) are excluded.
   */
  def isPublicHostname(host: String): Boolean =
    host != "localhost" && !host.matches("""\d{1,3}(\.\d{1,3}){3}""") && !host.contains(":")

}
