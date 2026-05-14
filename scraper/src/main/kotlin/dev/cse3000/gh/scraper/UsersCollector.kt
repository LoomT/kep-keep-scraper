package dev.cse3000.gh.scraper

import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.io.JsonlSink
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovers GitHub logins by scanning every `<tag>-*.jsonl` file already on disk
 * for this repo (everything other phases have written so far), fetches each
 * unique login's metadata via `GET /users/{login}`, and emits to the
 * `<tag>-users` stream.
 *
 * The login-extraction walks every JSON object recursively and picks up any
 * string-typed `login` field, so it covers `user.login`, `author.login`,
 * `committer.login`, `assignee.login`, `actor.login`, GraphQL `author.login`,
 * etc. — without per-stream knowledge.
 *
 * 404 (deleted/renamed account) is treated as a soft failure — logged at WARN,
 * skipped, the rest of the batch continues.
 *
 * Designed to be the *last* (but before orgs) phase a scraper runs: by then the other phases have
 * written their JSONLs to disk (so login discovery is complete). Callers should
 * call `sink.flushAll()` immediately before invoking this so any buffered writes
 * become visible to the file scan.
 */
class UsersCollector(
    private val client: GithubClient,
    private val sink: JsonlSink,
) {
    private val log = LoggerFactory.getLogger(UsersCollector::class.java)

    suspend fun run(tag: String, normalizedDir: Path, limit: Int? = null) {
        val outStream = "$tag-users"
        val outFileName = "$outStream.jsonl"
        if (!Files.isDirectory(normalizedDir)) {
            log.warn(
                "Users phase: {} does not exist; skipping (run other phases first to populate it).",
                normalizedDir,
            )
            return
        }
        val sourceFiles = Files.list(normalizedDir).use { stream ->
            stream
                .filter { p ->
                    val name = p.fileName.toString()
                    name.startsWith("$tag-") && name.endsWith(".jsonl") && name != outFileName
                }
                .sorted()
                .toList()
        }
        if (sourceFiles.isEmpty()) {
            log.warn(
                "Users phase: no '{}-*.jsonl' streams found in {}. Did you scrape with other phases first?",
                tag, normalizedDir,
            )
            return
        }
        val logins = sortedSetOf<String>()
        for (f in sourceFiles) extractLogins(f, into = logins)
        log.info(
            "Users phase: discovered {} distinct logins from {} stream(s) (tag={})",
            logins.size, sourceFiles.size, tag,
        )
        val effective = if (limit != null) logins.take(limit) else logins.toList()
        var processed = 0
        var skipped404 = 0
        for (login in effective) {
            if (fetchOne(outStream, login)) processed++ else skipped404++
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

    private fun extractLogins(path: Path, into: MutableSet<String>) {
        Files.newBufferedReader(path).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.isBlank()) continue
                val element = runCatching { JSON.parseToJsonElement(line) }.getOrNull() ?: continue
                walkLogins(element, into)
            }
        }
    }

    private fun walkLogins(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                val login = (element["login"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                if (!login.isNullOrBlank()) into += login
                for (v in element.values) walkLogins(v, into)
            }

            is JsonArray -> for (v in element) walkLogins(v, into)
            else -> {} // primitive / null
        }
    }

    /** Returns true if the user was successfully fetched and emitted; false on 404. */
    private suspend fun fetchOne(stream: String, login: String): Boolean {
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
        sink.emit(stream, element, mapOf("login" to login))
        return true
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
