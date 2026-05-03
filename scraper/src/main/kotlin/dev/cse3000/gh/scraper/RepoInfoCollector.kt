package dev.cse3000.gh.scraper

import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.io.JsonlSink
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * One-shot fetch of `/repos/{owner}/{repo}` — emits the raw repo metadata
 * (default branch, description, stars, license, timestamps, etc.) to the
 * `<repoTag>-repo-info` stream. Cheap (1 request, ETag-cached on reruns).
 */
class RepoInfoCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
    private val owner: String,
    private val repo: String,
    private val repoTag: String,
) {
    private val log = LoggerFactory.getLogger("${RepoInfoCollector::class.java.name}.$repoTag")

    suspend fun run() {
        val slug = "$owner/$repo"
        log.info("Fetching repo info for {}", slug)
        val info = client.getJson(client.apiUrl("/repos/$slug")).jsonObject
        sink.emit("$repoTag-repo-info", info, mapOf("repo" to slug))
    }
}
