package dev.cse3000.rq3

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens the synced shared SQLite database. Default location is
 * `<repo-root>/data/shared/proposals.db` (populated by `:rq3:syncSharedDb`).
 * The `:rq3:run` task is configured to use the repo root as its working
 * directory so that the relative `data/shared/proposals.db` resolves to
 * the unified data dir shared by all the scrapers.
 *
 * Override at runtime with `-PsharedDbPath=...` (forwarded to the JVM as
 * `-DsharedDbPath=...`).
 */
object SharedDb {
    fun resolvePath(): Path {
        val override = System.getProperty("sharedDbPath")?.takeIf { it.isNotBlank() }
        if (override != null) return Paths.get(override).toAbsolutePath()
        return Paths.get("data", "shared", "proposals.db").toAbsolutePath()
    }

    fun open(path: Path = resolvePath()): Connection {
        require(Files.exists(path)) {
            "Shared database not found at $path. Run `:rq3:syncSharedDb -PsharedDbPath=...` first."
        }
        return DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    }
}
