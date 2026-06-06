package dev.cse3000.complexity

import dev.cse3000.complexity.analyzer.runLizard
import dev.cse3000.complexity.analyzer.runScc
import dev.cse3000.complexity.config.ProjectConfig
import dev.cse3000.complexity.config.quarterlySnapshots
import dev.cse3000.complexity.db.ComplexityDb
import dev.cse3000.complexity.git.addWorktreeSparse
import dev.cse3000.complexity.git.removeWorktree
import dev.cse3000.complexity.git.resolveSnapshots
import dev.cse3000.gh.git.RepoMirror
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

private val log = LoggerFactory.getLogger("dev.cse3000.complexity.MainKt")

fun main() {
    val configs = ProjectConfig.loadAll()
    log.info("Loaded {} project configs", configs.size)

    val dataDir = Paths.get("data", "complexity").toAbsolutePath()
    Files.createDirectories(dataDir)
    val reposDir = dataDir.resolve("repos")
    Files.createDirectories(reposDir)
    val worktreeDir = dataDir.resolve("worktrees")
    Files.createDirectories(worktreeDir)
    val dbPath = dataDir.resolve("complexity.db")

    ComplexityDb(dbPath).use { db ->
        db.applySchema()
        for (config in configs) {
            runCatching { processProject(db, config, reposDir, worktreeDir) }
                .onFailure { log.error("Failed to process {}: {}", config.ownerRepo, it.message, it) }
        }
    }

    log.info("Done. Results at {}", dbPath)
}

private fun processProject(
    db: ComplexityDb,
    config: ProjectConfig,
    reposDir: Path,
    worktreeDir: Path,
) {
    log.info("=== {} ({}mo ago, subfolder={}) ===", config.ownerRepo, config.monthsAgo, config.subfolder)

    val mirror = RepoMirror(config.owner, config.repo, reposDir)
    runBlocking { mirror.ensureUpToDate() }

    db.upsertProject(config)

    val snapshotDates = quarterlySnapshots(monthsAgo = config.monthsAgo)
    log.info(
        "{}: {} quarterly snapshots ({} -> {})",
        config.ownerRepo,
        snapshotDates.size,
        snapshotDates.last(),
        snapshotDates.first()
    )

    mirror.use {
        val snapshots = resolveSnapshots(mirror.repository, snapshotDates)
        log.info("{}: resolved {} commits", config.ownerRepo, snapshots.size)

        for (snap in snapshots) {
            if (db.snapshotExists(config.projectId, snap.commitSha)) {
                log.info("  {} ({}) already analysed, skipping", snap.label, snap.commitSha.take(8))
                continue
            }

            log.info("  analysing {} -> {} ({})", snap.label, snap.commitSha.take(8), snap.commitTime)
            val wtPath = worktreeDir.resolve("${config.owner}_${config.repo}_${snap.commitSha.take(8)}")

            try {
                addWorktreeSparse(mirror.gitDir, snap.commitSha, wtPath, config.subfolder)

                val analysisPath = if (config.subfolder != null) wtPath.resolve(config.subfolder) else wtPath
                if (!analysisPath.exists()) {
                    log.warn("  subfolder {} does not exist at {}, skipping", config.subfolder, snap.commitSha.take(8))
                    continue
                }

                val snapshotId = db.insertSnapshot(config.projectId, snap)

                runCatching { runLizard(analysisPath, wtPath) }
                    .onSuccess { funcs ->
                        db.insertFunctionMetrics(snapshotId, funcs)
                        log.info("    lizard: {} functions", funcs.size)
                    }
                    .onFailure { log.error("    lizard failed: {}", it.message) }

                runCatching { runScc(analysisPath, wtPath) }
                    .onSuccess { files ->
                        db.insertFileMetrics(snapshotId, files)
                        log.info("    scc: {} files", files.size)
                    }
                    .onFailure { log.error("    scc failed: {}", it.message) }

            } finally {
                removeWorktree(mirror.gitDir, wtPath)
                if (wtPath.exists()) wtPath.toFile().deleteRecursively()
            }
        }
    }
}
