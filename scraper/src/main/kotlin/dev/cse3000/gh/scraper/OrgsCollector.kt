package dev.cse3000.gh.scraper

import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.concurrent.parallelFetch
import dev.cse3000.gh.io.JsonlSink
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two-step organisation scraper that runs after the [UsersCollector]:
 *
 * 1. For every login already in `<tag>-users.jsonl`, fetches `/users/{login}/orgs`
 *    (the user's `organizations_url`) and emits the response to
 *    `<tag>-user-orgs.jsonl`. Each row is the raw orgs array wrapped in
 *    `{"orgs": [...]}` plus the `_login` meta — that's the user→orgs membership.
 *
 * 2. Across every user's response, collects the distinct org logins and fetches
 *    `/orgs/{org}` for each, emitting the full org details to `<tag>-orgs.jsonl`.
 *
 * Loader-side mapping of users → orgs is the end goal: the loader can use the
 * membership rows from step 1 directly, plus enrich with `users[i].company` and
 * private-domain `users[i].email` strings (those don't surface as GitHub
 * organisations but represent real affiliations).
 *
 * 404s on either endpoint (deleted user / renamed org) are soft-failed: logged
 * WARN and skipped. ETag caching makes re-runs cheap.
 */
class OrgsCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
    private val concurrency: Int = 8,
) {
    private val log = LoggerFactory.getLogger(OrgsCollector::class.java)

    suspend fun run(tag: String, normalizedDir: Path, limit: Int? = null) {
        val usersFile = normalizedDir.resolve("$tag-users.jsonl")
        if (!Files.isRegularFile(usersFile)) {
            log.warn(
                "Orgs phase: '{}' not found; run the users phase first or include it in this run.",
                usersFile,
            )
            return
        }

        val userOrgsStream = "$tag-user-orgs"
        val orgsStream = "$tag-orgs"

        val userLogins = readUserLogins(usersFile)
        val effectiveUsers = if (limit != null) userLogins.take(limit) else userLogins
        log.info("Orgs phase: {} user(s) to query for memberships (tag={})", effectiveUsers.size, tag)

        // Phase 1: fetch /users/{login}/orgs in parallel; accumulate distinct org logins
        // from all responses. The collect block runs single-threaded so the
        // `orgLogins` set and counters don't need synchronization.
        val orgLogins = sortedSetOf<String>()
        var processedUsers = 0
        var skippedUserOrgs404 = 0
        parallelFetch(effectiveUsers, concurrency) { login ->
            fetchUserOrgs(userOrgsStream, login)
        }.collect { (_, orgsForUser) ->
            if (orgsForUser == null) {
                skippedUserOrgs404++
            } else {
                for (orgRef in orgsForUser) {
                    val orgLogin = (orgRef as? JsonObject)
                        ?.get("login")
                        ?.let { it as? JsonPrimitive }
                        ?.takeIf { it.isString }
                        ?.content
                    if (!orgLogin.isNullOrBlank()) orgLogins += orgLogin
                }
            }
            processedUsers++
            if (processedUsers % 100 == 0) {
                log.info(
                    "User-orgs progress: {}/{} users processed ({} skipped 404, distinct orgs so far: {}, rate-limit remaining: {})",
                    processedUsers,
                    effectiveUsers.size,
                    skippedUserOrgs404,
                    orgLogins.size,
                    client.rateLimiter.remainingSnapshot,
                )
            }
        }
        log.info(
            "Orgs phase: discovered {} distinct orgs from {} user(s) ({} skipped 404)",
            orgLogins.size, processedUsers, skippedUserOrgs404,
        )

        // Phase 2: fetch /orgs/{org} per discovered org login, also in parallel.
        var processedOrgs = 0
        var skippedOrgs404 = 0
        parallelFetch(orgLogins.toList(), concurrency) { orgLogin ->
            fetchOrg(orgsStream, orgLogin)
        }.collect { (_, ok) ->
            if (ok) processedOrgs++ else skippedOrgs404++
            val total = processedOrgs + skippedOrgs404
            if (total % 100 == 0) {
                log.info(
                    "Orgs progress: {}/{} processed ({} skipped 404, rate-limit remaining: {})",
                    total, orgLogins.size, skippedOrgs404, client.rateLimiter.remainingSnapshot,
                )
            }
        }
        log.info(
            "Orgs phase done: {} orgs fetched, {} skipped (404)",
            processedOrgs, skippedOrgs404,
        )
    }

    private fun readUserLogins(usersFile: Path): List<String> {
        val logins = sortedSetOf<String>()
        Files.newBufferedReader(usersFile).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                val obj = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val login = (obj["login"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!login.isNullOrBlank()) logins += login
            }
        }
        return logins.toList()
    }

    /**
     * Fetches `/users/{login}/orgs` (caps `per_page=100` — no realistic GitHub user has more
     * than 100 public org memberships) and emits the response to [stream]. Returns the
     * org refs array on success, null on 404. Wraps the bare JSON array as
     * `{"orgs": [...]}` so the JsonlSink's standard meta-injection (which only runs on
     * objects) attaches `_login` and `_scraped_at`.
     */
    private suspend fun fetchUserOrgs(stream: String, login: String): JsonArray? {
        val url = client.apiUrl("/users/$login/orgs", mapOf("per_page" to "100"))
        val element = try {
            client.getJson(url)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404 Not Found") == true) {
                log.warn("User '{}' not found (404) when fetching orgs", login)
                return null
            }
            throw e
        }
        val orgsArray = element as? JsonArray
        if (orgsArray == null) {
            log.warn("User '{}' /orgs returned unexpected non-array JSON; emitting nothing", login)
            return null
        }
        sink.emit(stream, JsonObject(mapOf("orgs" to orgsArray)), mapOf("login" to login))
        return orgsArray
    }

    /** Returns true if the org was successfully fetched and emitted; false on 404. */
    private suspend fun fetchOrg(stream: String, orgLogin: String): Boolean {
        val url = client.apiUrl("/orgs/$orgLogin")
        val element = try {
            client.getJson(url)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404 Not Found") == true) {
                log.warn("Org '{}' not found (404), skipping", orgLogin)
                return false
            }
            throw e
        }
        sink.emit(stream, element, mapOf("login" to orgLogin))
        return true
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
