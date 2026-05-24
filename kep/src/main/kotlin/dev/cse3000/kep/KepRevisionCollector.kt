package dev.cse3000.kep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.git.RevisionWalker
import dev.cse3000.gh.io.ScrapeContext
import org.slf4j.LoggerFactory

class KepRevisionCollector(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KepRevisionCollector::class.java)

    suspend fun run(incremental: Boolean) {
        RepoMirror(KepScraper.OWNER, KepScraper.REPO, ctx.reposDir).use { mirror ->
            mirror.ensureUpToDate()
            val sinceSha = if (incremental) ctx.gitCursor.get(mirror.slug) else null
            log.info("KEP revision walk starting (since={})", sinceSha?.take(8))
            val walker = RevisionWalker(mirror, ::isKepFile)
            walker.walk(sinceSha).collect { row ->
                ctx.sink.emit("kep-proposal-revisions", row, mapOf("repo" to mirror.slug))
            }
            walker.headSha()?.let { ctx.gitCursor.set(mirror.slug, it) }
            log.info("KEP revision walk done")
        }
    }

    internal fun isKepFile(path: String): Boolean = isKepFilePath(path)
}

/**
 * Predicate matching paths the KEP scraper considers proposal-bearing. Modern
 * KEPs live at `keps/<sig>/<dirs>/{kep.yaml,README.md}` — 3+ slashes under
 * `keps/`. Older single-file layouts and back-compat redirect stubs (one-line
 * `.md` files at a shallower depth) are rejected at HEAD enumeration; their
 * history is still reached via rename-following on the modern README.
 */
internal fun isKepFilePath(path: String): Boolean {
    if (!path.startsWith("keps/")) return false
    if (path.startsWith("keps/prod-readiness")) return false
    if (path.contains("NNNN-kep-template")) return false
    if (path.count { it == '/' } < 3) return false
    return path.endsWith("README.md", ignoreCase = true) || path.endsWith("kep.yaml", ignoreCase = true)
}
