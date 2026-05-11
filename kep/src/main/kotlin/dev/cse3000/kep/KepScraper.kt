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
        val genericScraper = GenericScraper(ctx, OWNER, REPO, TAG)
        coroutineScope {
            // Same split as KeepScraper: delegate non-proposal, non-commit, non-users,
            // non-orgs phases to GenericScraper; run commits ourselves with per-path filtering
            // on kep.yaml + README files; we run users + orgs again
            // at the end to ensure that they run after proposals and commits
            val genericPhases = applicable -
                    ScrapePhase.PROPOSALS - ScrapePhase.COMMITS -
                    ScrapePhase.USERS - ScrapePhase.ORGS
            if (genericPhases.isNotEmpty()) {
                launch { genericScraper.run(incremental, limit, genericPhases) }
            }
            if (ScrapePhase.PROPOSALS in applicable) {
                launch { KepRevisionCollector(ctx).run() }
            }
            if (ScrapePhase.COMMITS in applicable) {
                launch { KepCommitsCollector(ctx).run(limit) }
            }
        }
        val laterPhases = phases.intersect(setOf(ScrapePhase.USERS, ScrapePhase.ORGS))
        genericScraper.run(incremental, limit, laterPhases)
    }
}
