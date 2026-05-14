package dev.cse3000.loader

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Emits `data.db` — a fresh SQLite database created from [schemaSource] and populated
 * with every row in [Rows]. The output is byte-for-byte reproducible per input; an
 * existing `data.db` at the target path is overwritten.
 *
 * Inserts run in a single transaction with `foreign_keys=OFF` (same one-shot push as
 * [SqlWriter]) and use prepared-statement batches for throughput. Re-running against
 * an already-populated `data.db` is not supported — delete the file or pick a new
 * `outDir` instead.
 */
class DbWriter(private val outDir: Path) {
    fun write(rows: Rows, schemaSource: Path?) {
        require(schemaSource != null && Files.exists(schemaSource)) {
            "DbWriter needs a schema.sql; pass -PschemaPath=... or -PmonorepoRoot=... so it can be located."
        }
        Files.createDirectories(outDir)
        val dbPath = outDir.resolve("data.db")
        Files.deleteIfExists(dbPath)

        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.autoCommit = false
            applySchema(conn, Files.readString(schemaSource))

            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }

            insertProjects(conn, rows.projects)
            insertPersons(conn, rows.persons)
            insertOrganisations(conn, rows.organisations)
            insertPersonUsernames(conn, rows.personUsernames)
            insertAffiliations(conn, rows.affiliations)
            insertProposals(conn, rows.proposals)
            insertProposalRevisions(conn, rows.proposalRevisions)
            insertProposalRevisionAuthors(conn, rows.proposalRevisionAuthors)
            insertStageHistory(conn, rows.stageHistory)
            insertRelatedProposals(conn, rows.relatedProposals)
            insertComments(conn, rows.comments)

            conn.commit()
        }
    }

    /**
     * Naively splits [script] on top-level `;` boundaries. Good enough for our schema
     * (no embedded semicolons inside string literals or triggers); reject anything more
     * exotic so we notice quickly if schema.sql gains complexity.
     */
    private fun applySchema(conn: Connection, script: String) {
        val statements = script.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.lines().all { line -> line.trim().startsWith("--") || line.isBlank() } }
        conn.createStatement().use { stmt ->
            for (s in statements) stmt.execute(s)
        }
    }

    // ─── Per-table inserts ─────────────────────────────────────────────────

    private fun insertProjects(conn: Connection, items: List<Project>) =
        batch(
            conn,
            "INSERT INTO Project(project_id, project_name, enhancement_proposal_name, copyright) VALUES (?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.projectName)
            ps.setString(3, it.enhancementProposalName)
            ps.setString(4, it.copyright)
        }

    private fun insertPersons(conn: Connection, items: List<Person>) =
        batch(
            conn,
            "INSERT INTO Person(person_id, full_name) VALUES (?, ?)",
            items,
        ) { ps, it ->
            ps.setLong(1, it.personId)
            ps.setStringOrNull(2, it.fullName)
        }

    private fun insertOrganisations(conn: Connection, items: List<Organisation>) =
        batch(
            conn,
            "INSERT INTO Organisation(organisation_id, organisation_name) VALUES (?, ?)",
            items,
        ) { ps, it ->
            ps.setLong(1, it.organisationId)
            ps.setString(2, it.organisationName)
        }

    private fun insertPersonUsernames(conn: Connection, items: List<PersonUsername>) =
        batch(
            conn,
            "INSERT INTO PersonUsername(person_id, domain, username, real_name) VALUES (?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setLong(1, it.personId)
            ps.setString(2, it.domain)
            ps.setString(3, it.username)
            ps.setStringOrNull(4, it.realName)
        }

    private fun insertAffiliations(conn: Connection, items: List<Affiliation>) =
        batch(
            conn,
            "INSERT INTO Affiliation(organisation_id, person_id) VALUES (?, ?)",
            items,
        ) { ps, it ->
            ps.setLong(1, it.organisationId)
            ps.setLong(2, it.personId)
        }

    private fun insertProposals(conn: Connection, items: List<Proposal>) =
        batch(
            conn,
            "INSERT INTO Proposal(project_id, proposal_id, topic, proposal_type) VALUES (?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.proposalId)
            ps.setStringOrNull(3, it.topic)
            ps.setStringOrNull(4, it.proposalType)
        }

    private fun insertProposalRevisions(conn: Connection, items: List<ProposalRevision>) =
        batch(
            conn,
            "INSERT INTO ProposalRevision(project_id, proposal_id, revision_index, title, created_at, content, implemented_at_version) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.proposalId)
            ps.setInt(3, it.revisionIndex)
            ps.setString(4, it.title)
            ps.setString(5, it.createdAt)
            ps.setString(6, it.content)
            ps.setStringOrNull(7, it.implementedAtVersion)
        }

    private fun insertProposalRevisionAuthors(conn: Connection, items: List<ProposalRevisionAuthor>) =
        batch(
            conn,
            "INSERT INTO ProposalRevisionAuthor(project_id, proposal_id, revision_index, author_id) VALUES (?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.proposalId)
            ps.setInt(3, it.revisionIndex)
            ps.setLong(4, it.authorId)
        }

    private fun insertStageHistory(conn: Connection, items: List<StageHistory>) =
        batch(
            conn,
            "INSERT INTO StageHistory(project_id, proposal_id, stage_index, normalised_status, raw_status, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.proposalId)
            ps.setInt(3, it.stageIndex)
            ps.setString(4, it.normalizedStatus)
            ps.setStringOrNull(5, it.rawStatus)
            ps.setString(6, it.createdAt)
        }

    private fun insertRelatedProposals(conn: Connection, items: List<RelatedProposal>) =
        batch(
            conn,
            "INSERT INTO RelatedProposal(project_id, proposal_id, related_project_id, related_proposal_id, type) " +
                    "VALUES (?, ?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setInt(1, it.projectId)
            ps.setString(2, it.proposalId)
            ps.setInt(3, it.relatedProjectId)
            ps.setString(4, it.relatedProposalId)
            ps.setString(5, it.type)
        }

    private fun insertComments(conn: Connection, items: List<Comment>) =
        batch(
            conn,
            "INSERT INTO Comment(comment_id, author_id, project_id, proposal_id, comment_on_comment_id, created_at, content) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
            items,
        ) { ps, it ->
            ps.setLong(1, it.commentId)
            ps.setLong(2, it.authorId)
            ps.setInt(3, it.projectId)
            ps.setString(4, it.proposalId)
            if (it.commentOnCommentId != null) ps.setLong(5, it.commentOnCommentId) else ps.setNull(5, Types.BIGINT)
            ps.setStringOrNull(6, it.createdAt)
            ps.setString(7, it.content)
        }

    private inline fun <T> batch(
        conn: Connection,
        sql: String,
        items: List<T>,
        bind: (PreparedStatement, T) -> Unit,
    ) {
        if (items.isEmpty()) return
        conn.prepareStatement(sql).use { ps ->
            for (it in items) {
                bind(ps, it)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }
}

private fun PreparedStatement.setStringOrNull(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}
