package gitbucket.core.util

/**
 * Mixed into services and controllers that need to resolve GitBucket's home-derived
 * directories. Defaults to the JVM-wide `Directory` instance so existing behavior is
 * unchanged; override `directory` (typically via a constructor parameter on the concrete
 * class doing the mixing-in) to provide an isolated `Directory` instead, e.g. in tests.
 *
 * Declared as its own trait, rather than having each service/controller declare its own
 * `directory` default independently, so that a class mixing in several of them inherits one
 * unambiguous `directory` member instead of hitting a diamond-inheritance conflict between
 * unrelated identical-looking definitions.
 */
trait DirectoryProvider {
  protected def directory: Directory = gitbucket.core.util.Directory
}
