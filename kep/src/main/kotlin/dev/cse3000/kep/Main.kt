package dev.cse3000.kep

import dev.cse3000.gh.cli.parseScrapeArgs
import dev.cse3000.gh.cli.runScrape
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.cse3000.kep.Main")

fun main(args: Array<String>): Unit = runBlocking {
    val parsed = parseScrapeArgs(args, "kep")
    runScrape(log, "${KepScraper.OWNER}/${KepScraper.REPO}", parsed) { ctx ->
        KepScraper(ctx).run(parsed.mode == "update", parsed.limit, parsed.phases)
    }
}
