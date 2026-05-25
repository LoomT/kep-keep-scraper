package dev.cse3000.keep

import dev.cse3000.gh.cli.parseScrapeArgs
import dev.cse3000.gh.cli.runScrape
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.cse3000.keep.MainKt")

fun main(args: Array<String>): Unit = runBlocking {
    val parsed = parseScrapeArgs(args, "keep")
    runScrape(log, "${KeepScraper.OWNER}/${KeepScraper.REPO}", parsed) { ctx ->
        KeepScraper(ctx).run(parsed.mode == "update", parsed.limit, parsed.phases)
    }
}
