package dev.cse3000.gh.cli

import dev.cse3000.gh.scraper.UsersCollector
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("dev.cse3000.gh.cli.UsersMain")

fun main(): Unit = runBlocking {
    val inputProp = System.getProperty("input")
        ?: error("Pass -Pinput=path/to/logins.txt (one GitHub login per line; '#' starts a comment).")
    val inputPath = Paths.get(inputProp).toAbsolutePath()
    require(Files.exists(inputPath)) { "Input file not found: $inputPath" }

    val mode = (System.getProperty("mode") ?: "update").lowercase()
    require(mode in setOf("full", "update")) { "--mode must be 'full' or 'update' (got '$mode')" }
    val limit = System.getProperty("limit")?.toIntOrNull()?.also {
        require(it > 0) { "limit must be a positive integer" }
    }
    // Default to the unified <repo-root>/data so users.jsonl ends up alongside keep-*.jsonl /
    // kep-*.jsonl in `<root>/data/normalized/`, which is what the loader reads from.
    val dataDir: Path = System.getProperty("dataDir")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath() }
        ?: Paths.get("data").toAbsolutePath()

    val logins = readLogins(inputPath)
    log.info("Loaded {} distinct logins from {}", logins.size, inputPath)

    val args = ScrapeArgs(
        mode = mode,
        limit = limit,
        phases = emptySet(), // unused by the body, but the runner ignores it
        dataDir = dataDir,
    )

    runScrape(log, repoSlug = "github-users", args = args) { ctx ->
        UsersCollector(ctx.client, ctx.sink).run(logins, limit)
    }
}

private fun readLogins(path: Path): List<String> =
    Files.readAllLines(path)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .distinct()
        .sorted()
