package dev.cse3000.gh.cli

import dev.cse3000.gh.scraper.GenericScraper
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("dev.cse3000.gh.cli.Main")

fun main(args: Array<String>): Unit = runBlocking {
    val flags = args.filter { it.startsWith("--") }
    val usage =
        "Usage: scraper --repo=owner/name [--mode=full|update] [--limit=N] [--include=phase,...] [--dataDir=PATH]"
    val repoSlug = flags.firstOrNull { it.startsWith("--repo=") }?.substringAfter("=")
        ?: error("--repo=owner/name is required.\n$usage")
    val (owner, repo) = repoSlug.split('/').also {
        require(it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank()) {
            "--repo must be of the form 'owner/name' (got '$repoSlug')"
        }
    }
    val parsed = parseScrapeArgs(args, "scraper", allowProposals = false).let {
        // Default dataDir for the generic scraper: data/<owner>/<repo>/
        if (it.dataDir == null) it.copy(dataDir = Paths.get("data", owner, repo).toAbsolutePath())
        else it
    }
    runScrape(log, "$owner/$repo", parsed) { ctx ->
        GenericScraper(ctx, owner, repo).run(parsed.mode == "update", parsed.limit, parsed.phases)
    }
}
