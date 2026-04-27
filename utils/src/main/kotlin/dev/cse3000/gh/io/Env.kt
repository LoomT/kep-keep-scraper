package dev.cse3000.gh.io

import java.nio.file.Path
import java.nio.file.Paths

object Env {
    fun githubToken(): String =
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
            ?: error("GITHUB_TOKEN environment variable must be set.")

    fun dataDir(): Path {
        val override = System.getenv("SCRAPER_DATA_DIR")?.takeIf { it.isNotBlank() }
        return Paths.get(override ?: "data").toAbsolutePath()
    }
}
