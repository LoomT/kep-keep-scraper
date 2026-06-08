package dev.cse3000.complexity.git

import dev.cse3000.complexity.config.quarterLabel
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.absolutePathString

private val log = LoggerFactory.getLogger("dev.cse3000.complexity.git")

data class SnapshotInfo(
    val quarterDate: LocalDate,
    val label: String,
    val commitSha: String,
    val commitTime: Instant,
)

/**
 * Walks the commit history once and matches each quarterly [dates] to the latest
 * commit whose commit-time is on or before that date. [dates] must be sorted
 * newest-first (the output of [dev.cse3000.complexity.config.semiAnnualSnapshots]).
 */
fun resolveSnapshots(repo: Repository, dates: List<LocalDate>): List<SnapshotInfo> {
    val head = repo.resolve("HEAD") ?: return emptyList()
    val instants = dates.map { it.atStartOfDay(ZoneOffset.UTC).toInstant() }

    val matched = arrayOfNulls<Pair<String, Instant>>(instants.size)
    var dateIdx = 0

    RevWalk(repo).use { rw ->
        rw.sort(RevSort.COMMIT_TIME_DESC)
        rw.isFirstParent = true
        rw.markStart(rw.parseCommit(head))
        for (commit in rw) {
            if (dateIdx >= instants.size) break
            val commitTime = Instant.ofEpochSecond(commit.commitTime.toLong())
            while (dateIdx < instants.size && commitTime <= instants[dateIdx]) {
                matched[dateIdx] = commit.name to commitTime
                dateIdx++
            }
        }
    }

    val seen = mutableSetOf<String>()
    return dates.indices.mapNotNull { i ->
        val (sha, time) = matched[i] ?: return@mapNotNull null
        if (!seen.add(sha)) return@mapNotNull null
        SnapshotInfo(dates[i], quarterLabel(dates[i]), sha, time)
    }
}

/**
 * git worktree add with sparse checkout if [subfolder] is not null
 *
 * `core.protectNTFS=false` is set since some old CPython revisions had a weird file name `"Remove .pyc files..."` :/
 */
fun addWorktreeSparse(bareRepo: Path, sha: String, target: Path, subfolder: String?) {
    Files.createDirectories(target.parent)

    if (subfolder == null) {
        runGit(
            bareRepo,
            "-c", "core.protectNTFS=false",
            "-c", "core.longpaths=true",
            "worktree", "add", "--detach", target.absolutePathString(), sha
        )
        return
    }

    // 1. Create worktree without checking files out
    runGit(
        bareRepo,
        "-c", "core.protectNTFS=false",
        "-c", "core.longpaths=true",
        "worktree", "add", "--no-checkout", "--detach", target.absolutePathString(), sha
    )

    // 2. Configure sparse-checkout in the new worktree (run from inside it)
    //    --cone is faster and well-suited for "just this directory"
    runGit(target, "sparse-checkout", "init", "--cone")
    runGit(target, "sparse-checkout", "set", subfolder)

    // 3. Now actually populate the working tree
    runGit(target, "checkout")
}

fun removeWorktree(bareRepo: Path, target: Path) {
    runCatching { runGit(bareRepo, "worktree", "remove", "--force", target.absolutePathString()) }
        .onFailure { log.warn("worktree remove failed: {}", it.message) }
    runCatching { runGit(bareRepo, "worktree", "prune") }
}

private fun runGit(workDir: Path, vararg args: String) {
    val cmd = listOf("git") + args.toList()
    val proc = ProcessBuilder(cmd)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    val rc = proc.waitFor()
    if (rc != 0) error("git ${args.first()} failed (rc=$rc): $output")
}
