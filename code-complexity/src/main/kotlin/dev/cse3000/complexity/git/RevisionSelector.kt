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
 * newest-first (the output of [dev.cse3000.complexity.config.quarterlySnapshots]).
 */
fun resolveSnapshots(repo: Repository, dates: List<LocalDate>): List<SnapshotInfo> {
    val head = repo.resolve("HEAD") ?: return emptyList()
    val instants = dates.map { it.atStartOfDay(ZoneOffset.UTC).toInstant() }

    val matched = arrayOfNulls<Pair<String, Instant>>(instants.size)
    var dateIdx = 0

    RevWalk(repo).use { rw ->
        rw.sort(RevSort.COMMIT_TIME_DESC)
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

fun addWorktree(bareRepo: Path, sha: String, target: Path) {
    Files.createDirectories(target.parent)
    runGit(
        bareRepo,
        "-c", "core.protectNTFS=false", // old CPython revision has weird file name "Remove .pyc files..." :/
        "-c", "core.longpaths=true",
        "worktree", "add", "--detach", target.absolutePathString(), sha
    )
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
