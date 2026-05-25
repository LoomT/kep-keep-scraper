package dev.cse3000.gh.cli

import dev.cse3000.gh.io.RunManifest
import dev.cse3000.gh.io.RunManifestWriter
import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.launchStopFileWatcher
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val SHUTDOWN_TIMEOUT = 30.seconds

/**
 * Runs a scrape body inside the standard scaffolding shared by every scraper main:
 *
 * - Builds a [ScrapeContext] (using `args.dataDir` if set, else `Env.dataDir()`).
 * - Registers a JVM shutdown hook that cancels the root job and waits up to 30s.
 * - Launches the stop-file watcher (`<dataDir>/STOP`) and a 60s periodic flush.
 * - Wraps [body] in try/catch/finally with NonCancellable cleanup so a cancelled
 *   scrape still flushes ETag/cursor state and writes a run manifest with
 *   `cancelled: true`.
 *
 * Must be called from inside a [CoroutineScope] (typically `runBlocking { ... }`).
 * The scope's job is the one cancelled on shutdown — the launched stop-file
 * watcher and flush coroutines become its children via structured concurrency.
 */
suspend fun CoroutineScope.runScrape(
    log: Logger,
    repoSlug: String,
    args: ScrapeArgs,
    body: suspend (ScrapeContext) -> Unit,
) {
    val rootJob = coroutineContext.job
    val started = Instant.now().toString()
    val ctx = if (args.dataDir != null) ScrapeContext.create(args.dataDir) else ScrapeContext.create()
    val errors = mutableListOf<String>()
    var cancelled = false

    val shutdownHook = Thread {
        log.warn("Shutdown signal received; cancelling scrape gracefully (up to {})", SHUTDOWN_TIMEOUT)
        rootJob.cancel(CancellationException("Shutdown signal received"))
        runBlocking {
            val finished = withTimeoutOrNull(SHUTDOWN_TIMEOUT) { rootJob.join() }
            if (finished == null) log.warn("Graceful shutdown timed out after {}", SHUTDOWN_TIMEOUT)
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    val stopWatcherJob = launchStopFileWatcher(ctx.dataDir.resolve("STOP"))

    val flushJob = launch {
        while (isActive) {
            delay(60.seconds)
            withContext(NonCancellable) {
                runCatching { ctx.persist() }
                runCatching { ctx.sink.flushAll() }
            }
        }
    }

    try {
        log.info(
            "Starting {} scrape (repo={}, limit={}, phases={}, dataDir={})",
            args.mode, repoSlug, args.limit, args.phases.map { it.cli }, ctx.dataDir,
        )
        body(ctx)
        ctx.persist()
    } catch (ce: CancellationException) {
        cancelled = true
        errors += "cancelled: ${ce.message ?: "(no message)"}"
        log.warn("Scrape cancelled: {}", ce.message)
    } catch (e: Throwable) {
        errors += "fatal: ${e::class.simpleName}: ${e.message}"
        log.error("Scrape failed", e)
        throw e
    } finally {
        withContext(NonCancellable) {
            flushJob.cancel()
            runCatching { flushJob.join() }
            stopWatcherJob.cancel()
            runCatching { stopWatcherJob.join() }
            runCatching { ctx.persist() }
            val manifest = RunManifest(
                startedAt = started,
                finishedAt = Instant.now().toString(),
                mode = args.mode,
                repo = repoSlug,
                rateLimitRemainingAtEnd = ctx.client.rateLimiter.remainingSnapshot
                    .takeIf { it != Int.MAX_VALUE },
                requestsMade = ctx.client.requestsMade.get(),
                requests304 = ctx.client.requests304.get(),
                counts = ctx.sink.counts.toMap(),
                cancelled = cancelled,
                errors = errors,
            )
            val manifestPath = RunManifestWriter.write(ctx.dataDir.resolve("manifests"), manifest)
            ctx.close()
            log.info(
                "Done. cancelled={} requests={} 304s={} manifest={}",
                cancelled, manifest.requestsMade, manifest.requests304, manifestPath,
            )
        }
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
