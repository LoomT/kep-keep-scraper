package dev.cse3000.kep

import dev.cse3000.gh.io.RunManifest
import dev.cse3000.gh.io.RunManifestWriter
import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.gh.scraper.ScrapePhase
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("dev.cse3000.kep.Main")

fun main(args: Array<String>): Unit = runBlocking {
    val parsed = parseArgs(args)
    val incremental = parsed.mode == "update"
    val started = Instant.now().toString()
    val ctx = ScrapeContext.create()
    val errors = mutableListOf<String>()
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
        KepScraper(ctx).run(incremental, parsed.limit, parsed.phases)
        ctx.persist()
    } catch (e: Throwable) {
        errors += "fatal: ${e::class.simpleName}: ${e.message}"
        log.error("Scrape failed", e)
        throw e
    } finally {
        flushJob.cancel()
        flushJob.join()
        val manifest = RunManifest(
            startedAt = started,
            finishedAt = Instant.now().toString(),
            mode = parsed.mode,
            repo = "kubernetes/enhancements",
            rateLimitRemainingAtEnd = ctx.client.rateLimiter.remainingSnapshot
                .takeIf { it != Int.MAX_VALUE },
            requestsMade = ctx.client.requestsMade.get(),
            requests304 = ctx.client.requests304.get(),
            counts = ctx.sink.counts.toMap(),
            errors = errors,
        )
        val manifestPath = RunManifestWriter.write(ctx.dataDir.resolve("manifests"), manifest)
        ctx.close()
        log.info(
            "Done. requests={} 304s={} manifest={}",
            manifest.requestsMade, manifest.requests304, manifestPath
        )
    }
}

private data class Parsed(val mode: String, val limit: Int?, val phases: Set<ScrapePhase>)

private fun parseArgs(args: Array<String>): Parsed {
    val positional = args.filterNot { it.startsWith("--") }
    val flags = args.filter { it.startsWith("--") }
    val mode = positional.firstOrNull()?.lowercase() ?: "update"
    require(mode in setOf("full", "update")) {
        "Usage: kep [full|update] [--limit=N] [--include=phase1,phase2,...]   (got: ${args.joinToString(" ")})"
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
