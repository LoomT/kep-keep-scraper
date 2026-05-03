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
            val commonPhases = phases - ScrapePhase.PROPOSALS
            if (commonPhases.isNotEmpty()) {
                launch { GenericScraper(ctx, OWNER, REPO, TAG).run(incremental, limit, commonPhases) }
            }
            if (ScrapePhase.PROPOSALS in phases) {
                launch { KeepRevisionCollector(ctx).run() }
            }
        }
    }
}
