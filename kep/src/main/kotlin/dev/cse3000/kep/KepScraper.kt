package dev.cse3000.kep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.GenericScraper
import dev.cse3000.gh.scraper.ScrapePhase
import dev.cse3000.gh.scraper.UsersCollector
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
            // Same split as KeepScraper: delegate non-proposal, non-commit, non-users phases
            // to GenericScraper; run commits ourselves with per-path filtering on
            // kep.yaml + README files; run users ourselves at the end so it's keyed by our
            // own TAG (`kep-users.jsonl`).
            val genericPhases = applicable - ScrapePhase.PROPOSALS - ScrapePhase.COMMITS - ScrapePhase.USERS
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
        if (ScrapePhase.USERS in applicable) {
            ctx.sink.flushAll()
            UsersCollector(ctx.client, ctx.sink).run(TAG, ctx.dataDir.resolve("normalized"), limit)
        }
    }
}
