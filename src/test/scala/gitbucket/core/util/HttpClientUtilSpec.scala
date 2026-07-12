package gitbucket.core.util

import org.scalatest.funspec.AnyFunSpec

class HttpClientUtilSpec extends AnyFunSpec {

  describe("isPrivateAddress") {
    it("recognizes localhost, private, and link-local addresses") {
      assert(HttpClientUtil.isPrivateAddress("localhost") == true)
      assert(HttpClientUtil.isPrivateAddress("192.168.10.2") == true)
      assert(HttpClientUtil.isPrivateAddress("169.254.169.254") == true)
      assert(HttpClientUtil.isPrivateAddress("www.google.com") == false)
    }
  }

  describe("isLoopbackRedirectUri") {
    it("accepts any port on 127.0.0.1 or localhost over plain http") {
      assert(HttpClientUtil.isLoopbackRedirectUri("http://127.0.0.1:54321/callback"))
      assert(HttpClientUtil.isLoopbackRedirectUri("http://localhost:1/"))
    }

    it("rejects non-loopback, non-http, and lookalike hosts") {
      assert(!HttpClientUtil.isLoopbackRedirectUri("https://127.0.0.1/"))
      assert(!HttpClientUtil.isLoopbackRedirectUri("http://evil.example.com/"))
      assert(!HttpClientUtil.isLoopbackRedirectUri("http://127.0.0.1.evil.com/"))
    }

    it("ignores userinfo, path, and query on an otherwise-valid loopback uri") {
      assert(HttpClientUtil.isLoopbackRedirectUri("http://user:pass@127.0.0.1:8080/callback?code=abc"))
    }

    it("accepts IPv6 loopback addresses") {
      assert(HttpClientUtil.isLoopbackRedirectUri("http://[::1]:8080/callback"))
      assert(HttpClientUtil.isLoopbackRedirectUri("http://[0:0:0:0:0:0:0:1]/"))
    }

    it("rejects non-loopback IPv6 addresses") {
      assert(!HttpClientUtil.isLoopbackRedirectUri("http://[::2]/"))
      assert(!HttpClientUtil.isLoopbackRedirectUri("http://[2001:db8::1]/"))
    }

    it("rejects empty, blank, and unparseable uris") {
      assert(!HttpClientUtil.isLoopbackRedirectUri(""))
      assert(!HttpClientUtil.isLoopbackRedirectUri("not a uri"))
    }
  }

}
