package dev.cse3000.gh.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory

/**
 * Walks file-modification history for paths matching [pathPredicate].
 *
 * Strategy:
 * 1. Enumerate all paths matching the predicate at HEAD.
 * 2. For each path, walk `git log -- <path>` (optionally bounded by `not(sinceSha)`).
 * 3. For each (path, commit) pair, read the blob at that commit and emit a JsonObject row.
 *
 * Limitation: rename history is not followed; a file renamed in HEAD will only have
 * commits under its current name returned. Files deleted in HEAD are not walked.
 * Both are acceptable for KEEP/KEP where renames and proposal deletions are rare.
 */
class RevisionWalker(
    private val mirror: RepoMirror,
    private val pathPredicate: (String) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(RevisionWalker::class.java)

    /**
     * Returns the resolved HEAD SHA after the walk completes — caller persists it
     * as the new cursor for the next incremental run. Returns null if HEAD couldn't
     * be resolved (empty repo, broken refs, etc.).
     */
    fun headSha(): String? = mirror.repository.resolve("HEAD")?.name

    fun walk(sinceSha: String? = null): Flow<JsonObject> = flow {
        val repo = mirror.repository
        val headId: ObjectId = repo.resolve("HEAD") ?: run {
            log.warn("HEAD not resolvable in {}", mirror.gitDir)
            return@flow
        }
        val sinceId: ObjectId? = sinceSha?.let {
            runCatching { repo.resolve(it) }.getOrNull()
                ?: run {
                    log.warn("sinceSha {} not resolvable in {} — walking from start", it, mirror.gitDir)
                    null
                }
        }

        val pathsAtHead = listPathsAtHead(repo, headId)
        log.info(
            "Revision walk for {}: {} matching paths at HEAD ({}); since={}",
            mirror.slug, pathsAtHead.size, headId.name.take(8), sinceSha?.take(8),
        )

        var emittedTotal = 0
        var pathsProcessed = 0
        for (path in pathsAtHead) {
            val perPath = walkPath(repo, path, headId, sinceId)
            for (row in perPath) {
                emit(row)
                emittedTotal++
            }
            pathsProcessed++
            if (pathsProcessed % 50 == 0) {
                log.info(
                    "Revision walk progress: {}/{} paths, {} revisions emitted",
                    pathsProcessed, pathsAtHead.size, emittedTotal,
                )
            }
        }
        log.info(
            "Revision walk done for {}: {} paths, {} revisions emitted",
            mirror.slug, pathsAtHead.size, emittedTotal,
        )
    }.flowOn(Dispatchers.IO)

    private fun listPathsAtHead(repo: Repository, headId: ObjectId): List<String> {
        val paths = mutableListOf<String>()
        RevWalk(repo).use { rw ->
            rw.sort(RevSort.COMMIT_TIME_DESC, true)
            rw.sort(RevSort.REVERSE, true)
            val commit = rw.parseCommit(headId)
            TreeWalk(repo).use { tw ->
                tw.addTree(commit.tree)
                tw.isRecursive = true
                while (tw.next()) {
                    val p = tw.pathString
                    if (pathPredicate(p)) paths += p
                }
            }
        }
        return paths
    }

    private fun walkPath(
        repo: Repository,
        path: String,
        headId: ObjectId,
        sinceId: ObjectId?,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        Git(repo).use { git ->
            val cmd = git.log().add(headId).addPath(path)
            if (sinceId != null) cmd.not(sinceId)
            val commits = runCatching { cmd.call().toList() }.getOrElse {
                log.warn("git log failed for {}: {}", path, it.message)
                return rows
            }
            for (commit in commits) {
                val content = blobContent(repo, commit, path) ?: continue
                rows += buildRow(path, commit, content)
            }
        }
        return rows
    }

    private fun blobContent(repo: Repository, commit: RevCommit, path: String): String? {
        return TreeWalk.forPath(repo, path, commit.tree)?.use { tw ->
            val blobId = tw.getObjectId(0)
            runCatching { repo.open(blobId).bytes.toString(Charsets.UTF_8) }
                .onFailure { log.warn("Could not read blob {} at {}: {}", blobId.name, path, it.message) }
                .getOrNull()
        }
    }

    private fun buildRow(path: String, commit: RevCommit, contentText: String): JsonObject {
        val author = commit.authorIdent
        val committer = commit.committerIdent
        return buildJsonObject {
            put("path", JsonPrimitive(path))
            put("dir", JsonPrimitive(path.substringBeforeLast('/', "")))
            put("commit_sha", JsonPrimitive(commit.id.name))
            put("parent_shas", JsonArray(commit.parents.map { JsonPrimitive(it.id.name) }))
            put("author_name", JsonPrimitive(author.name))
            put("author_email", JsonPrimitive(author.emailAddress))
            put("authored_at", JsonPrimitive(author.whenAsInstant.toString()))
            put("committer_name", JsonPrimitive(committer.name))
            put("committer_email", JsonPrimitive(committer.emailAddress))
            put("committed_at", JsonPrimitive(committer.whenAsInstant.toString()))
            put("message", JsonPrimitive(commit.fullMessage))
            put("content_text", JsonPrimitive(contentText))
            put("content_size", JsonPrimitive(contentText.length))
        }
    }
}
