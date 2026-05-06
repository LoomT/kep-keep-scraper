package dev.cse3000.kep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.CommitsCollector
import org.slf4j.LoggerFactory

/**
 * Fetches commits from `kubernetes/enhancements` via the GitHub REST API,
 * scoped to KEP files only (`keps/{sig}/{kep-name}/{kep.yaml,README.md}`).
 * Path discovery uses the local git mirror.
 */
class KepCommitsCollector(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KepCommitsCollector::class.java)

    suspend fun run(incremental: Boolean, limit: Int? = null) {
        RepoMirror(KepScraper.OWNER, KepScraper.REPO, ctx.reposDir).use { mirror ->
            mirror.ensureUpToDate()
            val paths = mirror.listPathsAtHead { isKepFile(it) }
            log.info("KEP commits phase: discovered {} kep file paths at HEAD", paths.size)
            CommitsCollector(
                client = ctx.client,
                sink = ctx.sink,
                cursor = ctx.cursor,
                owner = KepScraper.OWNER,
                repo = KepScraper.REPO,
                repoTag = KepScraper.TAG,
                pathFilter = paths,
            ).run(incremental, limit)
        }
    }
}
