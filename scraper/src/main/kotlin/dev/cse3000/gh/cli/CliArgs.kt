package dev.cse3000.gh.cli

import dev.cse3000.gh.scraper.ScrapePhase
import java.nio.file.Path
import java.nio.file.Paths

data class ScrapeArgs(
    val mode: String,
    val limit: Int?,
    val phases: Set<ScrapePhase>,
    val dataDir: Path?,
)

/**
 * Parses the common CLI flags shared by every scraper main:
 *
 * - `--mode=full|update` (default `update`) — non-positional, consistent across all entry points.
 * - `--limit=N` — positive integer cap on top-level items per phase.
 * - `--include=phase1,phase2,...` — restrict phases. Unknown tokens fail. If [allowProposals]
 *   is false, passing `proposals` errors out and the result also strips it from defaults.
 * - `--dataDir=PATH` — override the data directory for this run.
 *
 * [name] is interpolated into the usage string.
 */
fun parseScrapeArgs(args: Array<String>, name: String, allowProposals: Boolean = true): ScrapeArgs {
    val flags = args.filter { it.startsWith("--") }
    val usage =
        "Usage: $name [--mode=full|update] [--limit=N] [--include=phase,...] [--dataDir=PATH]"
    val mode = flags.firstOrNull { it.startsWith("--mode=") }
        ?.substringAfter("=")
        ?.lowercase()
        ?: "update"
    require(mode in setOf("full", "update")) {
        "--mode must be 'full' or 'update' (got '$mode').\n$usage"
    }
    val limit = flags.firstOrNull { it.startsWith("--limit=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
        ?.also { require(it > 0) { "--limit must be a positive integer" } }
    val rawInclude = flags.firstOrNull { it.startsWith("--include=") }?.substringAfter("=")
    val rawTokens = rawInclude?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (!allowProposals) {
        require(rawTokens.none { it.equals("proposals", ignoreCase = true) }) {
            "--include=proposals is not supported here (use :keep:run or :kep:run instead)"
        }
    }
    val parsed = if (rawInclude.isNullOrBlank()) ScrapePhase.all else ScrapePhase.parseList(rawInclude)
    val phases = if (allowProposals) parsed else parsed - ScrapePhase.PROPOSALS
    val dataDir = flags.firstOrNull { it.startsWith("--dataDir=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() }
        ?.let { Paths.get(it).toAbsolutePath() }
    return ScrapeArgs(mode, limit, phases, dataDir)
}
