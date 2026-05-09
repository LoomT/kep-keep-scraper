package dev.cse3000.keep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.GenericScraper
import dev.cse3000.gh.scraper.ScrapePhase
import dev.cse3000.gh.scraper.UsersCollector
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
            // Delegate everything except proposals + commits + users to GenericScraper. We
            // override commits with KEEP-specific per-path filtering so we only fetch
            // commits that touched proposal markdown files; we run users ourselves at the
            // end so it's keyed by our own TAG (`keep-users.jsonl`) rather than the
            // generic owner_repo tag.
            val genericPhases = phases - ScrapePhase.PROPOSALS - ScrapePhase.COMMITS - ScrapePhase.USERS
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
        // Users runs sequentially AFTER everything else: it scans the just-emitted
        // keep-*.jsonl files for distinct logins.
        if (ScrapePhase.USERS in phases) {
            ctx.sink.flushAll()
            UsersCollector(ctx.client, ctx.sink).run(TAG, ctx.dataDir.resolve("normalized"), limit)
        }
    }
}
