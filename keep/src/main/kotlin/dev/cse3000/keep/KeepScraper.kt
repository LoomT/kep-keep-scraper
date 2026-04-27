package dev.cse3000.keep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.IssueAndPrCollector
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
            launch { KeepDiscussionsCollector(ctx).run(incremental, limit) }
        }
    }
}
