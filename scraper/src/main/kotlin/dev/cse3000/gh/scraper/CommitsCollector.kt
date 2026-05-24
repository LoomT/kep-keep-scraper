package dev.cse3000.gh.scraper

import dev.cse3000.gh.cache.SeenShas
import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.concurrent.parallelFetch
import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.io.JsonlSink
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.slf4j.LoggerFactory

/**
 * Incremental commits collector. Drives enumeration from the local git mirror
 * (already kept current by [RepoMirror.ensureUpToDate]), diffs reachable SHAs
 * against the persisted [SeenShas] set, and fetches each new commit individually
 * via `/repos/{slug}/commits/{sha}` to capture GitHub-side metadata (notably
 * `author.login`).
 *
 * Why local-git-driven instead of `?since=<date>`:
 * - Out-of-order commits (back-merges, rebase of a feature branch onto an older
 *   base) have committer dates older than the cursor and would be silently
 *   skipped by a date-based incremental.
 * - Force-pushed branches may dredge in new commits whose dates predate
 *   everything we've seen; same problem.
 *
 * `git fetch` (with the default `+`-prefixed refspec accepting non-fast-forward
 * updates) surfaces every commit reachable from current refs regardless of date
 * order or branch rewrites. SHA-set comparison then identifies exactly the
 * commits we haven't yet emitted.
 *
 * Orphaned SHAs (force-pushed away) remain in the persisted set so we never
 * refetch them. Their existing JSONL rows stay too; downstream consumers dedup
 * by `sha` and the contribution to the login → (name, email) map is unaffected.
 */
class CommitsCollector internal constructor(
    private val sink: JsonlSink,
    private val mirror: RepoMirror,
    private val repoTag: String,
    private val seenShas: SeenShas,
    private val concurrency: Int,
    private val rateLimitSnapshot: () -> Int,
    private val fetchCommit: suspend (sha: String) -> JsonObject?,
) {
    private val slug = mirror.slug
    private val log = LoggerFactory.getLogger("${CommitsCollector::class.java.name}.$repoTag")

    /** Production wiring: calls `/repos/{slug}/commits/{sha}` via the real GitHub client. */
    constructor(
        client: GithubClient,
        sink: JsonlSink,
        mirror: RepoMirror,
        repoTag: String,
        seenShas: SeenShas,
        concurrency: Int = 8,
    ) : this(
        sink = sink,
        mirror = mirror,
        repoTag = repoTag,
        seenShas = seenShas,
        concurrency = concurrency,
        rateLimitSnapshot = makeRateLimitSnapshot(client),
        fetchCommit = makeFetcher(client, mirror.slug),
    )

    companion object {
        private fun makeRateLimitSnapshot(client: GithubClient): () -> Int =
            { client.rateLimiter.remainingSnapshot }

        private fun makeFetcher(client: GithubClient, slug: String): suspend (String) -> JsonObject? = { sha ->
            val url = client.apiUrl("/repos/$slug/commits/$sha")
            runCatching { client.getJson(url).jsonObject }.getOrNull()
        }
    }

    suspend fun run(incremental: Boolean, limit: Int?) {
        val reachable = enumerateReachableShas(mirror.repository)
        val seen = if (incremental) seenShas.get(slug) else emptySet()
        val newShas = reachable - seen
        val toFetch = if (limit != null && newShas.size > limit) {
            log.info(
                "Commits phase: capping fetch to limit={} (would have fetched {})",
                limit, newShas.size,
            )
            newShas.toList().take(limit)
        } else newShas.toList()
        log.info(
            "Commits phase starting (slug={}, incremental={}, reachable={}, seen={}, new={}, rate-limit remaining={})",
            slug, incremental, reachable.size, seen.size, toFetch.size, rateLimitSnapshot(),
        )

        if (toFetch.isEmpty()) {
            log.info("Commits phase done: nothing new to fetch")
            return
        }

        var emitted = 0
        parallelFetch(toFetch, concurrency) { sha ->
            runCatching { fetchCommit(sha) }.getOrNull()
        }.collect { (sha, obj) ->
            if (obj != null) {
                sink.emit("$repoTag-commits", obj, mapOf("repo" to slug))
                seenShas.add(slug, sha)
                emitted++
                if (emitted % 100 == 0) {
                    log.info(
                        "Commits progress: {}/{} fetched (rate-limit remaining={})",
                        emitted, toFetch.size, rateLimitSnapshot(),
                    )
                }
            } else {
                log.warn("Failed to fetch commit {}/{}", slug, sha.take(8))
            }
        }
        log.info("Commits phase done: {}/{} new commits emitted", emitted, toFetch.size)
    }

    private fun enumerateReachableShas(repo: Repository): Set<String> {
        val shas = mutableSetOf<String>()
        RevWalk(repo).use { rw ->
            rw.sort(RevSort.COMMIT_TIME_DESC, true)
            for (ref in repo.refDatabase.refs) {
                val tip = ref.objectId ?: continue
                val commit = runCatching { rw.parseCommit(tip) }.getOrNull() ?: continue
                rw.markStart(commit)
            }
            for (commit in rw) shas += commit.id.name
        }
        return shas
    }
}
