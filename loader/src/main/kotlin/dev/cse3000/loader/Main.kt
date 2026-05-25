package dev.cse3000.loader

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("dev.cse3000.loader.MainKt")

fun main() {
    val keepProjectId = sysIntProp("keepProjectId")
        ?: error("Pass -PkeepProjectId=N (assigned project_id for KEEP)")
    val kepProjectId = sysIntProp("kepProjectId")
        ?: error("Pass -PkepProjectId=N (assigned project_id for KEP)")
    val dataDir = Paths.get(System.getProperty("dataDir") ?: "data")
    val outDir: Path = Paths.get(System.getProperty("out") ?: "build/export/keep-kep")
    // Schema source resolution, in priority order:
    // 1. -PschemaPath=<path> — explicit override (mirrors -PsharedDbPath in :analysis).
    // 2. -PmonorepoRoot=<path>/schema.sql — by default, it's the parent of rootProject.projectDir.
    val schemaSource = sequenceOf(
        System.getProperty("schemaPath")?.let(Paths::get),
        System.getProperty("monorepoRoot")?.let { Paths.get(it, "schema.sql") },
    ).filterNotNull().firstOrNull { java.nio.file.Files.exists(it) }

    assert(keepProjectId in 0..2_000 && kepProjectId in 0..2_000) {
        "Project IDs must be in [0,2000] due to SQLite's INTEGER size limitations."
    }
    val keepBaseId = keepProjectId * 1_000_000
    val kepBaseId = kepProjectId * 1_000_000

    log.info(
        "Loader run: keepProjectId={} kepProjectId={} dataDir={} out={} keepIdBase={} kepIdBase={}",
        keepProjectId, kepProjectId, dataDir, outDir, keepBaseId, kepBaseId,
    )

    val normalizedDir = dataDir.resolve("normalized")
    val keepRows = KeepMapper(
        projectId = keepProjectId,
        normalizedDir = normalizedDir,
        personIds = IdAllocator(keepBaseId),
        organisationIds = IdAllocator(keepBaseId),
        commentIds = IdAllocator(keepBaseId),
    ).mapAll()

    val kepRows = KepMapper(
        projectId = kepProjectId,
        normalizedDir = normalizedDir,
        personIds = IdAllocator(kepBaseId),
        organisationIds = IdAllocator(kepBaseId),
        commentIds = IdAllocator(kepBaseId),
    ).mapAll()

    val merged = keepRows + kepRows

    val exportTypes = parseExportTypes(System.getProperty("exportTypes"))
    log.info("Export types: {}", exportTypes.joinToString(","))

    if (ExportType.SQL in exportTypes) {
        SqlWriter(outDir).write(merged, schemaSource = schemaSource)
        log.info("Apply data.sql with: bash {}/apply.sh /path/to/proposals.db", outDir)
    }
    if (ExportType.DB in exportTypes) {
        DbWriter(outDir).write(merged, schemaSource = schemaSource)
        log.info("Wrote populated SQLite db at {}/data.db", outDir)
    }

    log.info(
        "Loaded into {} (Project={} Person={} PersonUsername={} Organisation={} Affiliation={} " +
                "Proposal={} ProposalRevision={} ProposalRevisionAuthor={} StageHistory={} RelatedProposal={} Comment={})",
        outDir,
        merged.projects.size,
        merged.persons.size,
        merged.personIdentifiers.size,
        merged.organisations.size,
        merged.affiliations.size,
        merged.proposals.size,
        merged.proposalRevisions.size,
        merged.proposalRevisionAuthors.size,
        merged.proposalStatuses.size,
        merged.relatedProposals.size,
        merged.comments.size,
    )
}

private enum class ExportType { DB, SQL }

/**
 * Parses the `-PexportTypes=<csv>` argument into the set of export targets.
 * Accepts `db`, `sql`, or any comma-separated mix (e.g. `db,sql`, `sql,db`).
 * Defaults to just `db` when the property is unset or blank. Unknown tokens
 * fail fast.
 */
private fun parseExportTypes(raw: String?): Set<ExportType> {
    val csv = raw?.trim()?.takeIf { it.isNotEmpty() } ?: "db"
    return csv.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { token ->
        when (token) {
            "db" -> ExportType.DB
            "sql" -> ExportType.SQL
            else -> error("Unknown -PexportTypes token '$token'; supported: db, sql, db,sql")
        }
    }.toSet()
}

private fun sysIntProp(name: String): Int? = System.getProperty(name)?.toIntOrNull()
