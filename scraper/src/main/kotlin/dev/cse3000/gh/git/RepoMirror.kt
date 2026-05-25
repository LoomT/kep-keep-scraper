package dev.cse3000.gh.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maintains a bare local mirror of a GitHub repository. First call to
 * [ensureUpToDate] performs a `git clone --bare`; subsequent calls do a
 * `git fetch` to pull new commits. Bare clones save disk (no working tree)
 * and are sufficient for log-walking.
 */
class RepoMirror(
    val owner: String,
    val repo: String,
    rootDir: Path,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(RepoMirror::class.java)
    val gitDir: Path = rootDir.resolve("${owner}_$repo.git")
    val slug: String get() = "$owner/$repo"

    private var git: Git? = null

    suspend fun ensureUpToDate(): Unit = withContext(Dispatchers.IO) {
        if (Files.exists(gitDir.resolve("HEAD"))) {
            log.info("Fetching {} into {}", slug, gitDir)
            val g = open()
            g.fetch().setProgressMonitor(NullProgressMonitor.INSTANCE).call()
        } else {
            log.info("Cloning {} into {} (bare)", slug, gitDir)
            Files.createDirectories(gitDir.parent)
            Git.cloneRepository()
                .setURI("https://github.com/$owner/$repo.git")
                .setDirectory(gitDir.toFile())
                .setBare(true)
                .setCloneAllBranches(false)
                .setProgressMonitor(NullProgressMonitor.INSTANCE)
                .call()
                .close()
        }
    }

    fun open(): Git {
        var g = git
        if (g == null) {
            g = Git.open(gitDir.toFile())
            git = g
        }
        return g
    }

    val repository: Repository get() = open().repository

    /**
     * Returns paths in the HEAD commit's tree matching [predicate]. Cheap, in-memory
     * walk over the tree (no commit history). Useful for the COMMITS / PROPOSALS
     * phases that want to enumerate proposal files without re-querying the GitHub
     * tree API.
     */
    fun listPathsAtHead(predicate: (String) -> Boolean): List<String> {
        val headId = repository.resolve("HEAD") ?: return emptyList()
        val paths = mutableListOf<String>()
        RevWalk(repository).use { rw ->
            val commit = rw.parseCommit(headId)
            TreeWalk(repository).use { tw ->
                tw.addTree(commit.tree)
                tw.isRecursive = true
                while (tw.next()) {
                    val p = tw.pathString
                    if (predicate(p)) paths += p
                }
            }
        }
        return paths
    }

    override fun close() {
        git?.close()
        git = null
    }
}
