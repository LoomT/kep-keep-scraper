package dev.cse3000.kep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.IssueAndPrCollector
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

    suspend fun run(incremental: Boolean, limit: Int? = null) {
        log.info("Scraping {}/{} (incremental={}, limit={})", OWNER, REPO, incremental, limit)
        coroutineScope {
            val collector = IssueAndPrCollector(
                client = ctx.client,
                sink = ctx.sink,
                cursor = ctx.cursor,
                owner = OWNER,
                repo = REPO,
                repoTag = TAG,
            )
            launch { collector.collectIssues(incremental, limit) }
            launch { collector.collectPullRequests(incremental, limit) }
            launch { KepDiscovery(ctx).run(limit) }
        }
    }
}
