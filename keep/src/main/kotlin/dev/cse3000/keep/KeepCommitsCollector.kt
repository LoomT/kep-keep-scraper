package dev.cse3000.keep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.CommitsCollector
import org.slf4j.LoggerFactory

/**
 * Fetches commits from `Kotlin/KEEP` via the GitHub REST API, but only for
 * proposal markdown files (anything under `proposals/` ending in `.md`). Path
 * discovery uses the local git mirror (already maintained by
 * `KeepRevisionCollector`); the resulting paths are passed to a generic
 * [CommitsCollector] which queries the API with `?path=` per file.
 */
class KeepCommitsCollector(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KeepCommitsCollector::class.java)

    suspend fun run(limit: Int? = null) {
        RepoMirror(KeepScraper.OWNER, KeepScraper.REPO, ctx.reposDir).use { mirror ->
            mirror.ensureUpToDate()
            val paths = mirror.listPathsAtHead { it.startsWith("proposals/") && it.endsWith(".md") }
            log.info("KEEP commits phase: discovered {} proposal paths at HEAD", paths.size)
            CommitsCollector(
                client = ctx.client,
                sink = ctx.sink,
                owner = KeepScraper.OWNER,
                repo = KeepScraper.REPO,
                repoTag = KeepScraper.TAG,
                pathFilter = paths,
            ).run(limit)
        }
    }
}
