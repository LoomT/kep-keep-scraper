package dev.cse3000.keep

import dev.cse3000.gh.io.RunManifest
import dev.cse3000.gh.io.RunManifestWriter
import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.ScrapePhase
import dev.cse3000.gh.scraper.launchStopFileWatcher
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("dev.cse3000.keep.Main")
private val SHUTDOWN_TIMEOUT = 30.seconds

fun main(args: Array<String>): Unit = runBlocking {
    val parsed = parseArgs(args)
    val incremental = parsed.mode == "update"
    val started = Instant.now().toString()
    val ctx = ScrapeContext.create()
    val errors = mutableListOf<String>()
    var cancelled = false

    val rootJob = coroutineContext.job
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

    val flushInterval = 60.seconds
    val flushJob = launch {
        while (isActive) {
            delay(flushInterval)
            withContext(NonCancellable) {
                runCatching { ctx.persist() }
                runCatching { ctx.sink.flushAll() }
            }
        }
    }
    try {
        log.info(
            "Starting {} scrape (limit={}, phases={}, dataDir={})",
            parsed.mode, parsed.limit, parsed.phases.map { it.cli }, ctx.dataDir,
        )
        KeepScraper(ctx).run(incremental, parsed.limit, parsed.phases)
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
                mode = parsed.mode,
                repo = "Kotlin/KEEP",
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

private data class Parsed(val mode: String, val limit: Int?, val phases: Set<ScrapePhase>)

private fun parseArgs(args: Array<String>): Parsed {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    val mode = positional.firstOrNull()?.lowercase() ?: "update"
    require(mode in setOf("full", "update")) {
        "Usage: keep [full|update] [--limit=N] [--include=phase1,phase2,...]   (got: ${args.joinToString(" ")})"
    }
    val limit = flags.firstOrNull { it.startsWith("--limit=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
        ?.also { require(it > 0) { "--limit must be a positive integer" } }
    val phases = ScrapePhase.parseList(
        flags.firstOrNull { it.startsWith("--include=") }?.substringAfter("=")
    )
    return Parsed(mode, limit, phases)
}
