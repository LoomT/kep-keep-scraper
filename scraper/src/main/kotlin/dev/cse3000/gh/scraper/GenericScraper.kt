package dev.cse3000.gh.scraper

import dev.cse3000.gh.io.ScrapeContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Repository-agnostic scraper that pulls issues, PRs, discussions and repo info
 * for any GitHub repo. Does NOT support the [ScrapePhase.PROPOSALS] phase — that
 * phase is meaningful only for repos with a known proposal layout (Kotlin/KEEP,
 * kubernetes/enhancements). Use the dedicated `:keep:run` / `:kep:run` tasks
 * for those.
 */
class GenericScraper(
    private val ctx: ScrapeContext,
    private val owner: String,
    private val repo: String,
    val tag: String = "${owner}_$repo".lowercase(),
) {
    private val log = LoggerFactory.getLogger(GenericScraper::class.java)

    suspend fun run(
        incremental: Boolean,
        limit: Int? = null,
        phases: Set<ScrapePhase> = ScrapePhase.all - ScrapePhase.PROPOSALS,
    ) {
        require(ScrapePhase.PROPOSALS !in phases) {
            "GenericScraper does not support the PROPOSALS phase (use :keep:run or :kep:run)."
        }
        log.info(
            "Scraping {}/{} (incremental={}, limit={}, phases={}, tag={})",
            owner, repo, incremental, limit, phases.map { it.cli }, tag,
        )
        coroutineScope {
            val collector = IssueAndPrCollector(
                client = ctx.client,
                sink = ctx.sink,
                cursor = ctx.cursor,
                owner = owner,
                repo = repo,
                repoTag = tag,
            )
            if (ScrapePhase.REPO_INFO in phases) {
                launch { RepoInfoCollector(ctx.client, ctx.sink, owner, repo, tag).run() }
            }
            if (ScrapePhase.ISSUES in phases) {
                launch { collector.collectIssues(incremental, limit) }
            }
            if (ScrapePhase.PRS in phases) {
                launch { collector.collectPullRequests(incremental, limit) }
            }
            if (ScrapePhase.DISCUSSIONS in phases) {
                launch { DiscussionsCollector(ctx, owner, repo, tag).run(incremental, limit) }
            }
            if (ScrapePhase.COMMITS in phases) {
                // Generic = full repo, no path filter. Named scrapers override this with
                // their own per-path commits collector.
                launch {
                    CommitsCollector(
                        client = ctx.client,
                        sink = ctx.sink,
                        cursor = ctx.cursor,
                        owner = owner,
                        repo = repo,
                        repoTag = tag,
                        pathFilter = null,
                    ).run(incremental, limit)
                }
            }
        }
    }
}
