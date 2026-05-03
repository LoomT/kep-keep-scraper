package dev.cse3000.gh.client

import dev.cse3000.gh.cache.EtagStore
import dev.cse3000.gh.cache.RawCache
import dev.cse3000.gh.io.Jsons
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GithubClient(
    private val token: String,
    private val cache: RawCache,
    private val etags: EtagStore,
    val rateLimiter: RateLimiter = RateLimiter(),
    private val userAgent: String = "cse3000-rq3-scraper/0.1",
    private val maxRetries: Int = 5,
) : AutoCloseable {

    private val baseUrl = "https://api.github.com"
    private val graphqlUrl = "https://api.github.com/graphql"

    val requestsMade = AtomicInteger(0)
    val requests304 = AtomicInteger(0)

    private val log = LoggerFactory.getLogger(GithubClient::class.java)

    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
        install(UserAgent) { agent = userAgent }
    }

    fun apiUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val qs = query.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
        }
        val sep = if (path.contains('?')) "&" else "?"
        val full = if (path.startsWith("http")) path else "$baseUrl$path"
        return if (qs.isEmpty()) full else "$full$sep$qs"
    }

    suspend fun getJson(url: String): JsonElement = rateLimiter.withPermit {
        fetchWithRetry(url).first
    }

    fun getJsonPaginated(initialUrl: String): Flow<JsonElement> = flow {
        var next: String? = initialUrl
        while (next != null) {
            val (body, link) = rateLimiter.withPermit { fetchWithRetry(next!!) }
            when (body) {
                is JsonArray -> body.forEach { emit(it) }
                else -> emit(body)
            }
            next = Paginator.nextLink(link)
        }
    }

    suspend fun runGraphQL(query: String, variables: JsonObject? = null): JsonElement {
        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }
        val body = Jsons.compact.encodeToString(JsonObject.serializer(), payload)
        return rateLimiter.withPermit { graphqlPost(body) }
    }

    /**
     * Probe a list endpoint to discover the total item count via the `Link: rel="last"`
     * header. Forces `per_page=1`. Bypasses ETag caching so we always get a fresh Link
     * header (304 responses don't include pagination links). Returns null on probe
     * failure or non-list responses.
     */
    suspend fun countListEndpoint(path: String, query: Map<String, String> = emptyMap()): Int? {
        val probeQuery = query.toMutableMap().apply {
            put("per_page", "1")
            put("page", "1")
        }
        val url = apiUrl(path, probeQuery)
        return rateLimiter.withPermit {
            val resp = http.get(url) { applyHeaders(this) }
            requestsMade.incrementAndGet()
            rateLimiter.observe(
                resp.headers["X-RateLimit-Remaining"],
                resp.headers["X-RateLimit-Reset"],
            )
            if (resp.status.value !in 200..299) {
                log.warn("countListEndpoint probe failed for {}: {}", url, resp.status)
                return@withPermit null
            }
            val link = resp.headers[HttpHeaders.Link]
            Paginator.lastPageNumber(link)?.let { return@withPermit it }
            // No rel="last" can mean (a) single-page result, or (b) GitHub omitted
            // it (notably for /issues?since=...). Distinguish via rel="next".
            if (Paginator.nextLink(link) != null) return@withPermit null
            (Jsons.compact.parseToJsonElement(resp.bodyAsText()) as? JsonArray)?.size
        }
    }

    private suspend fun fetchWithRetry(url: String): Pair<JsonElement, String?> {
        var attempt = 1
        while (true) {
            val etag = etags.get(url)
            val resp = http.get(url) {
                applyHeaders(this)
                if (etag != null) header(HttpHeaders.IfNoneMatch, etag)
            }
            requestsMade.incrementAndGet()
            rateLimiter.observe(
                resp.headers["X-RateLimit-Remaining"],
                resp.headers["X-RateLimit-Reset"],
            )
            val link = resp.headers[HttpHeaders.Link]
            when {
                resp.status == HttpStatusCode.NotModified -> {
                    requests304.incrementAndGet()
                    val cached = cache.get(url)
                        ?: error("304 with no cached body for $url; delete data/cache to recover")
                    return Jsons.compact.parseToJsonElement(cached) to link
                }
                resp.status.isSuccess() -> {
                    val text = resp.bodyAsText()
                    withContext(NonCancellable) {
                        cache.put(url, text)
                        resp.headers[HttpHeaders.ETag]?.let { etags.put(url, it) }
                    }
                    return Jsons.compact.parseToJsonElement(text) to link
                }
                resp.status.value == 403 || resp.status.value == 429 -> {
                    if (attempt > maxRetries) error("Rate-limited: $url (gave up after $maxRetries)")
                    val wait = retryDelay(resp)
                    log.warn(
                        "HTTP {} on {} — waiting {} (attempt {}/{})",
                        resp.status.value,
                        url,
                        wait,
                        attempt,
                        maxRetries,
                    )
                    delay(wait)
                    attempt++
                }
                resp.status.value in 500..599 -> {
                    if (attempt > maxRetries) error("5xx ${resp.status} on $url after $maxRetries attempts")
                    val backoff = (1L shl (attempt - 1).coerceAtMost(5)).seconds
                    log.warn(
                        "HTTP {} on {} — backing off {} (attempt {}/{})",
                        resp.status.value,
                        url,
                        backoff,
                        attempt,
                        maxRetries,
                    )
                    delay(backoff)
                    attempt++
                }
                resp.status == HttpStatusCode.NotFound -> {
                    error("404 Not Found: $url")
                }
                else -> error("Unexpected ${resp.status} on $url: ${resp.bodyAsText().take(500)}")
            }
        }
    }

    private suspend fun graphqlPost(body: String): JsonElement {
        var attempt = 1
        while (true) {
            val resp = http.post(graphqlUrl) {
                applyHeaders(this)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            requestsMade.incrementAndGet()
            rateLimiter.observe(
                resp.headers["X-RateLimit-Remaining"],
                resp.headers["X-RateLimit-Reset"],
            )
            when {
                resp.status.isSuccess() -> return Jsons.compact.parseToJsonElement(resp.bodyAsText())
                resp.status.value == 403 || resp.status.value == 429 -> {
                    if (attempt > maxRetries) error("GraphQL rate-limited, retries exhausted")
                    val wait = retryDelay(resp)
                    log.warn(
                        "GraphQL HTTP {} — waiting {} (attempt {}/{})",
                        resp.status.value,
                        wait,
                        attempt,
                        maxRetries
                    )
                    delay(wait)
                    attempt++
                }
                resp.status.value in 500..599 -> {
                    if (attempt > maxRetries) error("GraphQL 5xx exhausted: ${resp.status}")
                    val backoff = (1L shl (attempt - 1).coerceAtMost(5)).seconds
                    log.warn(
                        "GraphQL HTTP {} — backing off {} (attempt {}/{})",
                        resp.status.value,
                        backoff,
                        attempt,
                        maxRetries,
                    )
                    delay(backoff)
                    attempt++
                }
                else -> error("GraphQL ${resp.status}: ${resp.bodyAsText().take(500)}")
            }
        }
    }

    private fun applyHeaders(builder: HttpRequestBuilder) {
        builder.header(HttpHeaders.Authorization, "Bearer $token")
        builder.header(HttpHeaders.Accept, "application/vnd.github+json")
        builder.header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun retryDelay(resp: HttpResponse): Duration {
        resp.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let {
            return it.seconds + 500.milliseconds
        }
        resp.headers["X-RateLimit-Reset"]?.toLongOrNull()?.let { resetAt ->
            val waitSec = resetAt - System.currentTimeMillis() / 1000
            if (waitSec > 0) return (waitSec + 1).seconds
        }
        return 60.seconds
    }

    override fun close() {
        http.close()
    }
}
