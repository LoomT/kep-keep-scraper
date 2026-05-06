package dev.cse3000.gh.scraper

import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.io.JsonlSink
import org.slf4j.LoggerFactory

/**
 * Fetches `GET /users/{login}` for each of a manually-supplied list of GitHub
 * logins and emits the raw response to a `users.jsonl` stream. ETag caching,
 * rate-limit handling, retries, and graceful cancellation all come for free
 * via [GithubClient]; on a re-run, unchanged users return 304 (no quota cost).
 *
 * 404 (deleted/renamed account) is treated as a soft failure — logged at
 * WARN, that login is skipped, the rest of the batch continues.
 */
class UsersCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
) {
    private val log = LoggerFactory.getLogger(UsersCollector::class.java)

    suspend fun run(logins: List<String>, limit: Int? = null) {
        val effective = if (limit != null) logins.take(limit) else logins
        log.info("Users phase starting (count={})", effective.size)
        var processed = 0
        var skipped404 = 0
        for (login in effective) {
            if (fetchOne(login)) processed++ else skipped404++
            val total = processed + skipped404
            if (total % 25 == 0) {
                log.info(
                    "Users progress: {}/{} processed ({} skipped 404, rate-limit remaining: {})",
                    total, effective.size, skipped404, client.rateLimiter.remainingSnapshot,
                )
            }
        }
        log.info(
            "Users phase done: {} fetched, {} skipped (404)",
            processed, skipped404,
        )
    }

    /** Returns true if the user was successfully fetched and emitted; false on 404. */
    private suspend fun fetchOne(login: String): Boolean {
        val url = client.apiUrl("/users/$login")
        val element = try {
            client.getJson(url)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404 Not Found") == true) {
                log.warn("User '{}' not found (404), skipping", login)
                return false
            }
            throw e
        }
        sink.emit("users", element, mapOf("login" to login))
        return true
    }
}
