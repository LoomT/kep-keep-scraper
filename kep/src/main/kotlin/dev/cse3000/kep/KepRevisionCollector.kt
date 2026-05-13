package dev.cse3000.kep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.git.RevisionWalker
import dev.cse3000.gh.io.ScrapeContext
import kotlinx.serialization.json.JsonPrimitive
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
                val path = (row["path"] as? JsonPrimitive)?.content
                val stream = when {
                    path?.endsWith("/kep.yaml", ignoreCase = true) == true -> "kep-revisions-yaml"
                    path?.endsWith("/README.md", ignoreCase = true) == true -> "kep-revisions-readme"
                    else -> "kep-revisions-other"
                }
                ctx.sink.emit(stream, row, mapOf("repo" to mirror.slug))
            }
            walker.headSha()?.let { ctx.gitCursor.set(mirror.slug, it) }
            log.info("KEP revision walk done")
        }
    }
}
