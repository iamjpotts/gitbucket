package gitbucket.core.util

import java.io.File

/**
 * Resolves the default `gitbucket.home` the same way `Directory` always has: the
 * `-Dgitbucket.home=<path>` system property, then the `GITBUCKET_HOME` environment variable,
 * then the legacy `~/gitbucket` directory if it already exists, then `~/.gitbucket`.
 *
 * Kept outside `Directory` so the companion object below can call it while constructing its own
 * `Directory` superclass instance, before `Directory`'s own members are available.
 */
private[util] object DirectoryHomeResolver {
  def resolveDefault(): String = (System.getProperty("gitbucket.home") match {
    // -Dgitbucket.home=<path>
    case path if (path != null) => new File(path)
    case _                      =>
      scala.util.Properties.envOrNone("GITBUCKET_HOME") match {
        // environment variable GITBUCKET_HOME
        case Some(env) => new File(env)
        // default is HOME/.gitbucket
        case None => {
          val oldHome = new File(System.getProperty("user.home"), "gitbucket")
          if (oldHome.exists && oldHome.isDirectory && new File(oldHome, "version").exists) {
            // FileUtils.moveDirectory(oldHome, newHome)
            oldHome
          } else {
            new File(System.getProperty("user.home"), ".gitbucket")
          }
        }
      }
  }).getAbsolutePath
}

/**
 * Provides directory locations used by GitBucket, rooted at `GitBucketHome`.
 *
 * Instantiable (rather than a plain singleton `object`) so tests can construct an isolated
 * `Directory` pointed at a unique temporary directory instead of sharing the one JVM-wide home
 * directory. The companion `object Directory` below preserves the previous singleton behavior
 * for all existing production call sites.
 */
class Directory(val GitBucketHome: String) {

  val GitBucketConf = new File(GitBucketHome, "gitbucket.conf")

  val ActivityLog = new File(GitBucketHome, "activity.log")

  val RepositoryHome = s"${GitBucketHome}/repositories"

  val DatabaseHome = s"${GitBucketHome}/data"

  val PluginHome = s"${GitBucketHome}/plugins"

  val TemporaryHome = s"${GitBucketHome}/tmp"

  /**
   * Substance directory of the repository.
   */
  def getRepositoryDir(owner: String, repository: String): File =
    new File(s"${RepositoryHome}/${owner}/${repository}.git")

  /**
   * Directory for repository files.
   */
  def getRepositoryFilesDir(owner: String, repository: String): File =
    new File(s"${RepositoryHome}/${owner}/${repository}")

  /**
   * Directory for files which are attached to issue.
   */
  def getAttachedDir(owner: String, repository: String): File =
    new File(getRepositoryFilesDir(owner, repository), "comments")

  /**
   * Directory for released files
   */
  def getReleaseFilesDir(owner: String, repository: String): File =
    new File(getRepositoryFilesDir(owner, repository), "releases")

  /**
   * Directory for Git LFS files.
   */
  def getLfsDir(owner: String, repository: String): File =
    new File(getRepositoryFilesDir(owner, repository), "lfs")

  /**
   * Directory for files which store diff fragment
   */
  def getDiffDir(owner: String, repository: String): File =
    new File(getRepositoryFilesDir(owner, repository), "diff")

  /**
   * Directory for uploaded files by the specified user.
   */
  def getUserUploadDir(userName: String): File =
    new File(s"${GitBucketHome}/data/${userName}/files")

  /**
   * Root of temporary directories for the upload file.
   */
  def getTemporaryDir(sessionId: String): File =
    new File(s"${TemporaryHome}/_upload/${sessionId}")

  /**
   * Root of temporary directories for the specified repository.
   */
  def getTemporaryDir(owner: String, repository: String): File =
    new File(s"${TemporaryHome}/${owner}/${repository}")

  /**
   * Root of plugin cache directory. Plugin repositories are cloned into this directory.
   */
  def getPluginCacheDir(): File =
    new File(s"${TemporaryHome}/_plugins")

  /**
   * Substance directory of the wiki repository.
   */
  def getWikiRepositoryDir(owner: String, repository: String): File =
    new File(s"${RepositoryHome}/${owner}/${repository}.wiki.git")

}

/**
 * The JVM-wide default `Directory`, resolved from `-Dgitbucket.home` / `GITBUCKET_HOME` / the
 * user's home directory, exactly as `Directory` behaved before it became instantiable.
 */
object Directory extends Directory(DirectoryHomeResolver.resolveDefault())
