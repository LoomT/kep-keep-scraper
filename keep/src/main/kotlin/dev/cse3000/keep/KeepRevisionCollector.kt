package dev.cse3000.keep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.git.RevisionWalker
import dev.cse3000.gh.io.ScrapeContext
import org.slf4j.LoggerFactory

class KeepRevisionCollector(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KeepRevisionCollector::class.java)

    suspend fun run(incremental: Boolean) {
        RepoMirror(KeepScraper.OWNER, KeepScraper.REPO, ctx.reposDir).use { mirror ->
            mirror.ensureUpToDate()
            val sinceSha = if (incremental) ctx.gitCursor.get(mirror.slug) else null
            log.info("KEEP revision walk starting (since={})", sinceSha?.take(8))
            val walker = RevisionWalker(mirror) { path ->
                path.startsWith("proposals/") && path.endsWith(".md")
            }
            walker.walk(sinceSha).collect { row ->
                ctx.sink.emit("keep-proposal-revisions", row, mapOf("repo" to mirror.slug))
            }
            walker.headSha()?.let { ctx.gitCursor.set(mirror.slug, it) }
            log.info("KEEP revision walk done")
        }
    }
}
