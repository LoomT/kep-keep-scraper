package dev.cse3000.keep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.IssueAndPrCollector
import dev.cse3000.gh.scraper.RepoInfoCollector
import dev.cse3000.gh.scraper.ScrapePhase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class KeepScraper(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KeepScraper::class.java)

    companion object {
        const val OWNER = "Kotlin"
        const val REPO = "KEEP"
        const val TAG = "keep"
    }

    suspend fun run(incremental: Boolean, limit: Int? = null, phases: Set<ScrapePhase> = ScrapePhase.all) {
        log.info(
            "Scraping {}/{} (incremental={}, limit={}, phases={})",
            OWNER, REPO, incremental, limit, phases.map { it.cli },
        )
        coroutineScope {
            val collector = IssueAndPrCollector(
                client = ctx.client,
                sink = ctx.sink,
                cursor = ctx.cursor,
                owner = OWNER,
                repo = REPO,
                repoTag = TAG,
            )
            if (ScrapePhase.REPO_INFO in phases) {
                launch { RepoInfoCollector(ctx.client, ctx.sink, OWNER, REPO, TAG).run() }
            }
            if (ScrapePhase.ISSUES in phases) {
                launch { collector.collectIssues(incremental, limit) }
            }
            if (ScrapePhase.PRS in phases) {
                launch { collector.collectPullRequests(incremental, limit) }
            }
            if (ScrapePhase.DISCUSSIONS in phases) {
                launch { KeepDiscussionsCollector(ctx).run(incremental, limit) }
            }
            if (ScrapePhase.PROPOSALS in phases) {
                launch { KeepRevisionCollector(ctx).run() }
            }
        }
    }
}
