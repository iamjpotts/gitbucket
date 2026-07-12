package gitbucket.core.servlet

import java.sql.DriverManager

import org.scalatest.funsuite.AnyFunSuite

import scala.util.Using

/**
 * Covers `InitializeListener.checkNoAccountNamedLogin` in isolation, against a raw H2
 * in-memory connection rather than the full migrated schema — the guard runs before any
 * migration, so it must tolerate a database that doesn't have an ACCOUNT table yet.
 */
class InitializeListenerSpec extends AnyFunSuite {

  private def withMemoryConnection[T](f: java.sql.Connection => T): T = {
    org.h2.Driver.load()
    val url = s"jdbc:h2:mem:${getClass.getSimpleName}_${System.identityHashCode(new Object)};DB_CLOSE_DELAY=-1"
    Using.resource(DriverManager.getConnection(url, "sa", "")) { conn =>
      f(conn)
    }
  }

  test("does not throw on a brand-new database with no ACCOUNT table") {
    withMemoryConnection { conn =>
      InitializeListener.checkNoAccountNamedLogin(conn)
    }
  }

  test("does not throw when ACCOUNT exists but has no row named 'login'") {
    withMemoryConnection { conn =>
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE ACCOUNT (USER_NAME VARCHAR(100) PRIMARY KEY)")
      stmt.execute("INSERT INTO ACCOUNT (USER_NAME) VALUES ('root')")
      stmt.execute("INSERT INTO ACCOUNT (USER_NAME) VALUES ('alice')")
      stmt.close()

      InitializeListener.checkNoAccountNamedLogin(conn)
    }
  }

  test("throws when ACCOUNT has a row named 'login'") {
    withMemoryConnection { conn =>
      val stmt = conn.createStatement()
      stmt.execute("CREATE TABLE ACCOUNT (USER_NAME VARCHAR(100) PRIMARY KEY)")
      stmt.execute("INSERT INTO ACCOUNT (USER_NAME) VALUES ('root')")
      stmt.execute("INSERT INTO ACCOUNT (USER_NAME) VALUES ('login')")
      stmt.close()

      val exception = intercept[IllegalStateException] {
        InitializeListener.checkNoAccountNamedLogin(conn)
      }
      assert(
        exception.getMessage ==
          "An account with user name 'login' exists. This account must be renamed before the GitBucket upgrade can proceed."
      )
    }
  }
}
