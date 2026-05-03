package dev.cse3000.kep

import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.git.RevisionWalker
import dev.cse3000.gh.io.ScrapeContext
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

class KepRevisionCollector(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KepRevisionCollector::class.java)

    private fun isKepFile(path: String): Boolean {
        if (!path.startsWith("keps/")) return false
        val segments = path.split('/')
        // Want exactly keps/{sig}/{kep-name}/{file}
        if (segments.size != 4) return false
        val filename = segments[3]
        return filename == "kep.yaml" || filename == "README.md"
    }

    suspend fun run() {
        RepoMirror(KepScraper.OWNER, KepScraper.REPO, ctx.reposDir).use { mirror ->
            mirror.ensureUpToDate()
            val sinceSha = ctx.gitCursor.get(mirror.slug)
            log.info("KEP revision walk starting (since={})", sinceSha?.take(8))
            val walker = RevisionWalker(mirror, ::isKepFile)
            walker.walk(sinceSha).collect { row ->
                val path = (row["path"] as? JsonPrimitive)?.content
                val stream = when {
                    path?.endsWith("/kep.yaml") == true -> "kep-yaml-revisions"
                    path?.endsWith("/README.md") == true -> "kep-readme-revisions"
                    else -> "kep-revisions-other"
                }
                ctx.sink.emit(stream, row, mapOf("repo" to mirror.slug))
            }
            walker.headSha()?.let { ctx.gitCursor.set(mirror.slug, it) }
            log.info("KEP revision walk done")
        }
    }
}
