package gitbucket.core.util

import java.io.File
import org.scalatest.funspec.AnyFunSpec

class DirectorySpec extends AnyFunSpec {

  describe("GitBucketHome") {
    it("should set under target in test scope") {
      assert(Directory.GitBucketHome == new java.io.File("target/gitbucket_home_for_test").getAbsolutePath)
    }
  }
//  test("GitBucketHome should exists"){
//    new java.io.File(Directory.GitBucketHome).exists
//  }

  describe("a Directory instance constructed with a custom home") {
    val customHome = "/tmp/gitbucket-directory-spec-custom-home"
    val custom = new Directory(customHome)

    it("derives every path from the constructor argument, not from the JVM-wide gitbucket.home property") {
      assert(custom.GitBucketHome == customHome)
      assert(custom.RepositoryHome == s"$customHome/repositories")
      assert(custom.DatabaseHome == s"$customHome/data")
      assert(custom.GitBucketConf == new File(customHome, "gitbucket.conf"))
      assert(custom.getRepositoryDir("root", "test") == new File(s"$customHome/repositories/root/test.git"))
      assert(custom.getWikiRepositoryDir("root", "test") == new File(s"$customHome/repositories/root/test.wiki.git"))
    }

    it("does not affect the JVM-wide Directory instance") {
      assert(Directory.GitBucketHome == new java.io.File("target/gitbucket_home_for_test").getAbsolutePath)
      assert(custom.GitBucketHome != Directory.GitBucketHome)
    }
  }

}
