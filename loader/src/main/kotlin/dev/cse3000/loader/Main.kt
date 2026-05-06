package dev.cse3000.loader

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("dev.cse3000.loader.Main")

fun main() {
    val keepProjectId = sysIntProp("keepProjectId")
        ?: error("Pass -PkeepProjectId=N (your assigned project_id for KEEP)")
    val kepProjectId = sysIntProp("kepProjectId")
        ?: error("Pass -PkepProjectId=N (your assigned project_id for KEP)")
    val dataDir = Paths.get(System.getProperty("dataDir") ?: "data")
    val outDir: Path = Paths.get(System.getProperty("out") ?: "loader/build/export/keep-kep")
    val schemaSource = Paths.get("db-schema.txt")

    val keepBaseId = keepProjectId.toLong() * 1_000_000L
    val kepBaseId = kepProjectId.toLong() * 1_000_000L

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

    SqlWriter(outDir).write(merged, schemaSource = schemaSource.takeIf { java.nio.file.Files.exists(it) })

    log.info(
        "Wrote {} (Project={} Person={} PersonUsername={} Organisation={} Affiliation={} " +
                "Proposal={} ProposalRevision={} ProposalRevisionAuthor={} StageHistory={} RelatedProposal={} Comment={})",
        outDir,
        merged.projects.size,
        merged.persons.size,
        merged.personUsernames.size,
        merged.organisations.size,
        merged.affiliations.size,
        merged.proposals.size,
        merged.proposalRevisions.size,
        merged.proposalRevisionAuthors.size,
        merged.stageHistory.size,
        merged.relatedProposals.size,
        merged.comments.size,
    )
    log.info("Apply with: bash {}/apply.sh /path/to/proposals.db", outDir)
}

private fun sysIntProp(name: String): Int? = System.getProperty(name)?.toIntOrNull()
