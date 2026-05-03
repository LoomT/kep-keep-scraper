package dev.cse3000.kep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.IssueAndPrCollector
import dev.cse3000.gh.scraper.RepoInfoCollector
import dev.cse3000.gh.scraper.ScrapePhase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class KepScraper(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KepScraper::class.java)

    companion object {
        const val OWNER = "kubernetes"
        const val REPO = "enhancements"
        const val TAG = "kep"
    }

    suspend fun run(incremental: Boolean, limit: Int? = null, phases: Set<ScrapePhase> = ScrapePhase.all) {
        log.info(
            "Scraping {}/{} (incremental={}, limit={}, phases={})",
            OWNER, REPO, incremental, limit, phases.map { it.cli },
        )
        if (ScrapePhase.DISCUSSIONS in phases) {
            log.warn("--include=discussions is a no-op for KEP (kubernetes/enhancements doesn't use the Discussions tab)")
        }
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
            if (ScrapePhase.PROPOSALS in phases) {
                launch { KepRevisionCollector(ctx).run() }
            }
        }
    }
}
