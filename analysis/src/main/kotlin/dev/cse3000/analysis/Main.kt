package dev.cse3000.analysis

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.cse3000.analysis.MainKt")

private val TABLES = listOf(
    "Project",
    "Person",
    "Organisation",
    "PersonUsername",
    "Affiliation",
    "Proposal",
    "ProposalRevision",
    "ProposalRevisionAuthor",
    "StageHistory",
    "RelatedProposal",
    "Comment",
)

fun main() {
    val path = SharedDb.resolvePath()
    log.info("Opening shared database at {}", path)
    SharedDb.open(path).use { conn ->
        conn.createStatement().use { st ->
            for (table in TABLES) {
                val rs = runCatching { st.executeQuery("SELECT COUNT(*) FROM $table") }.getOrNull()
                if (rs == null) {
                    log.warn("{}: table not found in db (schema mismatch?)", table)
                    continue
                }
                rs.use { if (it.next()) log.info("{}: {} rows", table, it.getLong(1)) }
            }
        }
    }
}
