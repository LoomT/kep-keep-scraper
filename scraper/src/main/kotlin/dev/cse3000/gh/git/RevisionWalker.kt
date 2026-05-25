package dev.cse3000.gh.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.*
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory

/**
 * Walks file-modification history for paths matching [pathPredicate], following
 * file renames the way `git log --follow` does.
 *
 * Strategy:
 * 1. Enumerate all paths matching the predicate at HEAD.
 * 2. For each HEAD path, drive a [RevWalk] with a [FollowFilter] so the walk
 *    crosses rename boundaries; track the historical path via [RenameCallback].
 * 3. For each (commit, historical-path) pair, read the blob at the historical
 *    path and emit a JsonObject row. Rows carry both `head_path` (stable HEAD
 *    key for downstream grouping) and `path` (the path at that commit).
 *
 * Limitations: copy detection is not enabled (matches Git's `--follow` default);
 * rename detection's similarity heuristic can miss a rename whose contents were
 * heavily rewritten in the same commit; files deleted at HEAD are not walked.
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
        headPath: String,
        headId: ObjectId,
        sinceId: ObjectId?,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val pathHistory = mutableListOf(headPath)
        var renamedSinceLastCommit: String? = null

        RevWalk(repo).use { rw ->
            val diffConfig = repo.config.get(DiffConfig.KEY)
            val followFilter = FollowFilter.create(headPath, diffConfig)
            followFilter.renameCallback = object : RenameCallback() {
                override fun renamed(entry: DiffEntry) {
                    pathHistory += entry.oldPath
                    renamedSinceLastCommit = entry.oldPath
                }
            }
            rw.treeFilter = followFilter

            try {
                rw.markStart(rw.parseCommit(headId))
                if (sinceId != null) {
                    runCatching { rw.markUninteresting(rw.parseCommit(sinceId)) }
                        .onFailure {
                            log.warn(
                                "markUninteresting failed for since={} on {}: {}",
                                sinceId.name.take(8), headPath, it.message,
                            )
                        }
                }
            } catch (t: Throwable) {
                log.warn("revwalk init failed for {}: {}", headPath, t.message)
                return rows
            }

            val iter = rw.iterator()
            while (true) {
                val commit = runCatching { if (iter.hasNext()) iter.next() else null }
                    .getOrElse {
                        log.warn("revwalk failed for {}: {}", headPath, it.message)
                        null
                    } ?: break

                var pathAtCommit: String? = null
                var content: String? = null
                for (i in pathHistory.indices.reversed()) {
                    val candidate = pathHistory[i]
                    content = blobContent(repo, commit, candidate)
                    if (content != null) {
                        pathAtCommit = candidate
                        break
                    }
                }
                if (content != null && pathAtCommit != null) {
                    rows += buildRow(headPath, pathAtCommit, commit, content, renamedSinceLastCommit)
                }
                renamedSinceLastCommit = null
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

    private fun buildRow(
        headPath: String,
        path: String,
        commit: RevCommit,
        contentText: String,
        renamedFrom: String?,
    ): JsonObject {
        val author = commit.authorIdent
        val committer = commit.committerIdent
        return buildJsonObject {
            put("head_path", JsonPrimitive(headPath))
            put("head_dir", JsonPrimitive(headPath.substringBeforeLast('/', "")))
            put("path", JsonPrimitive(path))
            put("dir", JsonPrimitive(path.substringBeforeLast('/', "")))
            if (renamedFrom != null) put("renamed_from", JsonPrimitive(renamedFrom))
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
