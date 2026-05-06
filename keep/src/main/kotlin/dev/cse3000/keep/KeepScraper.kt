package dev.cse3000.keep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.GenericScraper
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
            // Delegate everything except proposals + commits to GenericScraper. We override
            // commits with KEEP-specific per-path filtering so we only fetch commits that
            // touched proposal markdown files.
            val genericPhases = phases - ScrapePhase.PROPOSALS - ScrapePhase.COMMITS
            if (genericPhases.isNotEmpty()) {
                launch { GenericScraper(ctx, OWNER, REPO, TAG).run(incremental, limit, genericPhases) }
            }
            if (ScrapePhase.PROPOSALS in phases) {
                launch { KeepRevisionCollector(ctx).run() }
            }
            if (ScrapePhase.COMMITS in phases) {
                launch { KeepCommitsCollector(ctx).run(incremental, limit) }
            }
        }
    }
}
