package dev.cse3000.gh.scraper

import dev.cse3000.gh.cache.SyncCursor
import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.io.JsonlSink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Shared logic that fetches issues, PRs, and their comments/reviews/timeline events
 * for any repo. Both KEEP and KEP scrapers reuse this — the GitHub object graph is
 * identical, the only repo-specific bits live in the per-module discovery code.
 */
class IssueAndPrCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
    private val cursor: SyncCursor,
    owner: String,
    repo: String,
    private val repoTag: String,
) {
    private val slug = "$owner/$repo"
    private val cursorKeyIssues = "$repoTag.issues.updated_at"
    private val cursorKeyPulls = "$repoTag.pulls.updated_at"
    private val log = LoggerFactory.getLogger("${IssueAndPrCollector::class.java.name}.$repoTag")

    suspend fun collectIssues(incremental: Boolean, limit: Int? = null) {
        val params = mutableMapOf(
            "state" to "all",
            "per_page" to "100",
            "sort" to "updated",
            "direction" to "asc",
        )
        if (incremental) cursor.get(cursorKeyIssues)?.let { params["since"] = it }
        val total = client.countListEndpoint("/repos/$slug/issues", params)
        val denominator: Any = limit ?: total ?: "?"
        log.info(
            "Issues phase starting (slug={}, incremental={}, since={}, limit={}, total={})",
            slug, incremental, params["since"], limit, total,
        )
        val url = client.apiUrl("/repos/$slug/issues", params)
        var maxUpdated: String? = null
        var processed = 0
        val flow = client.getJsonPaginated(url)
        val capped = if (limit != null) flow.take(limit) else flow
        capped.collect { item ->
            val obj = item.jsonObject
            sink.emit("$repoTag-issues", obj, mapOf("repo" to slug))
            obj["updated_at"]?.jsonPrimitive?.contentOrNull?.let { ts ->
                if (ts > (maxUpdated ?: "")) maxUpdated = ts
            }
            val number = obj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val isPr = obj["pull_request"] is JsonObject
            if (number != null) {
                val commentStream = if (isPr) "$repoTag-pr-issuecomments" else "$repoTag-issue-comments"
                coroutineScope {
                    launch { collectIssueComments(number, commentStream) }
                    launch { collectTimeline(number, isPr) }
                }
            }
            processed++
            if (processed % 25 == 0) {
                log.info(
                    "Issues progress: {}/{} processed (rate-limit remaining: {})",
                    processed, denominator, client.rateLimiter.remainingSnapshot,
                )
            }
        }
        maxUpdated?.let { cursor.advance(cursorKeyIssues, it) }
        log.info("Issues phase done: {} processed, max updated_at={}", processed, maxUpdated)
    }

    suspend fun collectPullRequests(incremental: Boolean, limit: Int? = null) {
        val params = mutableMapOf(
            "state" to "all",
            "per_page" to "100",
            "sort" to "updated",
            "direction" to "asc",
        )
        // The PRs list endpoint does not support `since`. We fall back to filtering client-side.
        val sinceCursor = if (incremental) cursor.get(cursorKeyPulls) else null
        val totalInRepo = client.countListEndpoint("/repos/$slug/pulls", params)
        val denominator: Any = limit ?: totalInRepo ?: "?"
        if (sinceCursor != null) {
            log.info(
                "PRs phase starting (slug={}, sinceCursor={}, limit={}, of {} total in repo — filter applied client-side)",
                slug, sinceCursor, limit, totalInRepo,
            )
        } else {
            log.info(
                "PRs phase starting (slug={}, incremental={}, limit={}, total={})",
                slug, incremental, limit, totalInRepo,
            )
        }
        val url = client.apiUrl("/repos/$slug/pulls", params)
        var maxUpdated: String? = null
        var processed = 0
        client.getJsonPaginated(url).collect { item ->
            val obj = item.jsonObject
            val updatedAt = obj["updated_at"]?.jsonPrimitive?.contentOrNull
            if (sinceCursor != null && updatedAt != null && updatedAt < sinceCursor) return@collect
            if (limit != null && processed >= limit) return@collect
            sink.emit("$repoTag-pulls", obj, mapOf("repo" to slug))
            updatedAt?.let { if (it > (maxUpdated ?: "")) maxUpdated = it }
            val number = obj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@collect
            collectPullDetails(number)
            processed++
            if (processed % 25 == 0) {
                log.info(
                    "PRs progress: {}/{} processed (rate-limit remaining: {})",
                    processed, denominator, client.rateLimiter.remainingSnapshot,
                )
            }
        }
        maxUpdated?.let { cursor.advance(cursorKeyPulls, it) }
        log.info("PRs phase done: {} processed, max updated_at={}", processed, maxUpdated)
    }

    private suspend fun collectPullDetails(prNumber: Int) = coroutineScope {
        val meta = mapOf("repo" to slug, "pr" to prNumber.toString())
        launch {
            val detail = client.getJson(client.apiUrl("/repos/$slug/pulls/$prNumber"))
            sink.emit("$repoTag-pull-detail", detail, meta)
        }
        launch {
            client.getJsonPaginated(client.apiUrl("/repos/$slug/pulls/$prNumber/files", mapOf("per_page" to "100")))
                .collect { sink.emit("$repoTag-pr-files", it, meta) }
        }
        launch {
            client.getJsonPaginated(client.apiUrl("/repos/$slug/pulls/$prNumber/reviews", mapOf("per_page" to "100")))
                .collect { sink.emit("$repoTag-pr-reviews", it, meta) }
        }
        launch {
            client.getJsonPaginated(client.apiUrl("/repos/$slug/pulls/$prNumber/comments", mapOf("per_page" to "100")))
                .collect { sink.emit("$repoTag-pr-review-comments", it, meta) }
        }
        launch {
            client.getJsonPaginated(client.apiUrl("/repos/$slug/pulls/$prNumber/commits", mapOf("per_page" to "100")))
                .collect { sink.emit("$repoTag-pr-commits", it, meta) }
        }
    }

    private suspend fun collectIssueComments(number: Int, stream: String) {
        client.getJsonPaginated(client.apiUrl("/repos/$slug/issues/$number/comments", mapOf("per_page" to "100")))
            .collect { sink.emit(stream, it, mapOf("repo" to slug, "issue" to number.toString())) }
    }

    private suspend fun collectTimeline(number: Int, isPr: Boolean) {
        val streamName = if (isPr) "$repoTag-pr-timeline" else "$repoTag-issue-timeline"
        client.getJsonPaginated(client.apiUrl("/repos/$slug/issues/$number/timeline", mapOf("per_page" to "100")))
            .collect { sink.emit(streamName, it, mapOf("repo" to slug, "number" to number.toString())) }
    }
}
