package dev.cse3000.kep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.GenericScraper
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
        if (ScrapePhase.DISCUSSIONS in phases) {
            log.warn("--include=discussions is a no-op for KEP (kubernetes/enhancements doesn't use the Discussions tab)")
        }
        // kubernetes/enhancements has discussions disabled; querying would error out via the GraphQL endpoint.
        val applicable = phases - ScrapePhase.DISCUSSIONS
        log.info(
            "Scraping {}/{} (incremental={}, limit={}, phases={})",
            OWNER, REPO, incremental, limit, applicable.map { it.cli },
        )
        coroutineScope {
            // Same split as KeepScraper: delegate non-proposal, non-commit phases to GenericScraper;
            // do commits ourselves with per-path filtering on the kep.yaml + README files.
            val genericPhases = applicable - ScrapePhase.PROPOSALS - ScrapePhase.COMMITS
            if (genericPhases.isNotEmpty()) {
                launch { GenericScraper(ctx, OWNER, REPO, TAG).run(incremental, limit, genericPhases) }
            }
            if (ScrapePhase.PROPOSALS in applicable) {
                launch { KepRevisionCollector(ctx).run() }
            }
            if (ScrapePhase.COMMITS in applicable) {
                launch { KepCommitsCollector(ctx).run(incremental, limit) }
            }
        }
    }
}
