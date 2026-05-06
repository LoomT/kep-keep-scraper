package dev.cse3000.gh.scraper

import dev.cse3000.gh.cache.SyncCursor
import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.io.JsonlSink
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Fetches commits via GitHub's REST `/repos/{slug}/commits` endpoint. Unlike
 * the local-git revision walker, this captures GitHub-specific metadata —
 * notably `author.login` / `committer.login` (the GitHub user mapped from
 * the git author email).
 *
 * If [pathFilter] is non-null, the collector issues one paginated request
 * per path with `?path=<p>` to restrict results to commits that touched that
 * path. With [pathFilter] null, the entire repo's commit history is fetched.
 *
 * Incremental: tracks the max `commit.committer.date` seen across all
 * fetched commits in the cursor key `<repoTag>.commits.committer_date` and
 * passes it as `?since=` on the next run. (GitHub's commit list endpoint
 * filters by committer date.)
 */
class CommitsCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
    private val cursor: SyncCursor,
    private val owner: String,
    private val repo: String,
    private val repoTag: String,
    private val pathFilter: List<String>? = null,
) {
    private val slug = "$owner/$repo"
    private val cursorKey = "$repoTag.commits.committer_date"
    private val log = LoggerFactory.getLogger("${CommitsCollector::class.java.name}.$repoTag")

    suspend fun run(incremental: Boolean, limit: Int? = null) {
        val sinceCursor = if (incremental) cursor.get(cursorKey) else null
        if (pathFilter == null) {
            log.info(
                "Commits phase starting (slug={}, incremental={}, since={}, limit={}, mode=full-repo)",
                slug, incremental, sinceCursor, limit,
            )
            collectStream(path = null, sinceCursor = sinceCursor, limit = limit)
        } else {
            log.info(
                "Commits phase starting (slug={}, incremental={}, since={}, limit={}, mode=per-path, paths={})",
                slug, incremental, sinceCursor, limit, pathFilter.size,
            )
            var processedAcrossPaths = 0
            var pathsDone = 0
            for (path in pathFilter) {
                if (limit != null && processedAcrossPaths >= limit) break
                val remaining = limit?.let { it - processedAcrossPaths }
                processedAcrossPaths += collectStream(path = path, sinceCursor = sinceCursor, limit = remaining)
                pathsDone++
                if (pathsDone % 50 == 0) {
                    log.info(
                        "Commits per-path progress: {}/{} paths done, {} total commits emitted (rate-limit remaining: {})",
                        pathsDone, pathFilter.size, processedAcrossPaths, client.rateLimiter.remainingSnapshot,
                    )
                }
            }
            log.info(
                "Commits phase done: {} paths, {} total commits emitted, max committer_date={}",
                pathFilter.size, processedAcrossPaths, cursor.get(cursorKey),
            )
        }
    }

    /** Returns the number of commits emitted from this stream. */
    private suspend fun collectStream(path: String?, sinceCursor: String?, limit: Int?): Int {
        val params = mutableMapOf("per_page" to "100")
        if (sinceCursor != null) params["since"] = sinceCursor
        if (path != null) params["path"] = path
        val url = client.apiUrl("/repos/$slug/commits", params)
        val flow = client.getJsonPaginated(url)
        val capped = if (limit != null) flow.take(limit) else flow
        var processed = 0
        capped.collect { item ->
            val obj = item.jsonObject
            val committerDate = obj["commit"]?.jsonObject
                ?.get("committer")?.jsonObject
                ?.get("date")?.jsonPrimitive?.contentOrNull
            val meta = buildMap {
                put("repo", slug)
                if (path != null) put("path", path)
            }
            sink.emit("$repoTag-commits", obj, meta)
            if (committerDate != null) cursor.advance(cursorKey, committerDate)
            processed++
        }
        return processed
    }
}
