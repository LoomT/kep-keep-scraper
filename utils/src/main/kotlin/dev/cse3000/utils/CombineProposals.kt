package dev.cse3000.utils

import org.intellij.lang.annotations.Language
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.*

private val log = LoggerFactory.getLogger("dev.cse3000.utils.CombineProposalsKt")

private const val CUTOFF = "2026-01-01T00:00:00Z"

private val TIME_FILTERED_TABLES = setOf("ProposalRevision", "ProposalStatus", "Comment")

// Order respects foreign-key dependencies, so inserts succeed with FKs enabled.
private val MERGE_TABLE_ORDER = listOf(
    "Project",
    "Person",
    "PersonIdentifier",
    "Organisation",
    "Affiliation",
    "Proposal",
    "ProposalRevision",
    "ProposalRevisionAuthor",
    "ProposalStatus",
    "RelatedProposal",
    "Comment",
)

private typealias TableSchema = Map<String, Set<String>>

fun main() {
    val sharedDir = resolveSharedDir()
    val schemaPath = resolveSchemaPath()
    val output = sharedDir.resolve("all_proposals.db")

    log.info("Schema reference: {}", schemaPath)
    log.info("Output database: {}", output)

    val reference = loadReferenceSchema(schemaPath)
    log.info("Reference has {} tables: {}", reference.size, reference.keys.sorted())

    val inputs = collectInputs(sharedDir)
    log.info("Found {} candidate database files", inputs.size)

    Files.deleteIfExists(output)
    openSqlite(output).use { out ->
        out.autoCommit = false
        applySchema(out, schemaPath)
        out.commit()

        var mergedFiles = 0
        var skippedFiles = 0
        for (input in inputs) {
            if (mergeFile(out, input, reference)) mergedFiles++ else skippedFiles++
        }
        log.info("Done. merged={} skipped={} -> {}", mergedFiles, skippedFiles, output)
    }
}

private fun resolveSharedDir(): Path {
    val override = System.getProperty("sharedDir")?.takeIf { it.isNotBlank() }
    if (override != null) return Paths.get(override).toAbsolutePath()
    return Paths.get("data", "shared").toAbsolutePath()
}

private fun resolveSchemaPath(): Path {
    val override = System.getProperty("schemaPath")?.takeIf { it.isNotBlank() }
    if (override != null) {
        val p = Paths.get(override).toAbsolutePath()
        require(Files.exists(p)) { "schemaPath does not exist: $p" }
        return p
    }
    val monorepoRoot = System.getProperty("monorepoRoot")?.takeIf { it.isNotBlank() }
    val candidates = buildList {
        if (monorepoRoot != null) add(Paths.get(monorepoRoot, "schema.sql"))
        add(Paths.get("..", "schema.sql"))
        add(Paths.get("schema.sql"))
    }
    return candidates.map { it.toAbsolutePath().normalize() }
        .firstOrNull { Files.exists(it) }
        ?: error("Could not find schema.sql. Pass -PschemaPath=... or -PmonorepoRoot=...")
}

private fun collectInputs(sharedDir: Path): List<Path> {
    val list = mutableListOf<Path>()
    val main = sharedDir.resolve("data.db")
    if (Files.exists(main)) list.add(main) else log.warn("Main data.db missing at {}", main)

    val otherDir = sharedDir.resolve("other_proposals")
    if (Files.isDirectory(otherDir)) {
        otherDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension.lowercase() in setOf("db", "sqlite", "sqlite3") }
            .sortedBy { it.name }
            .forEach { list.add(it) }
    } else {
        log.warn("other_proposals directory not found at {}", otherDir)
    }
    return list
}

private fun openSqlite(path: Path): Connection =
    DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

private fun applySchema(target: Connection, schemaPath: Path) {
    val sql = Files.readString(schemaPath)
    target.createStatement().use { st ->
        // schema.sql is pure DDL with no string-embedded semicolons, so a naive split is safe.
        for (stmt in sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            st.execute(stmt)
        }
    }
}

private fun loadReferenceSchema(schemaPath: Path): TableSchema {
    val tmp = Files.createTempFile("schema-ref-", ".db")
    Files.deleteIfExists(tmp)
    try {
        openSqlite(tmp).use { conn ->
            applySchema(conn, schemaPath)
            return readSchema(conn)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

private fun readSchema(conn: Connection): TableSchema {
    val tables = mutableListOf<String>()
    conn.createStatement().use { st ->
        st.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { rs -> while (rs.next()) tables += rs.getString(1) }
    }
    return tables.associateWith { readColumnNames(conn, it) }
}

private fun readColumnNames(conn: Connection, table: String): Set<String> {
    val cols = mutableSetOf<String>()
    val safe = table.replace("\"", "\"\"")
    conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(\"$safe\")").use { rs ->
            while (rs.next()) cols += rs.getString("name").lowercase()
        }
    }
    return cols
}

private fun mergeFile(target: Connection, source: Path, reference: TableSchema): Boolean {
    val actual = try {
        openSqlite(source).use { readSchema(it) }
    } catch (e: SQLException) {
        log.error("Skipping {}: failed to open ({})", source.name, e.message)
        return false
    }

    // Cheap pre-check: every expected table must exist and contain every expected column name.
    val missingTables = reference.keys - actual.keys
    if (missingTables.isNotEmpty()) {
        log.error("Skipping {}: missing tables {}", source.name, missingTables.sorted())
        return false
    }
    for ((table, refCols) in reference) {
        val missingCols = refCols - actual.getValue(table)
        if (missingCols.isNotEmpty()) {
            log.error("Skipping {}: table {} missing columns {}", source.name, table, missingCols.sorted())
            return false
        }
    }

    return tryInsert(target, source, reference)
}

/**
 * Attempt to copy every expected table inside a SAVEPOINT. If any row of any
 * table fails a target constraint (NOT NULL, CHECK, PK uniqueness, FK), or any
 * other SQL error occurs, the savepoint is rolled back and the whole file is
 * skipped. SQLite's column-affinity rules quietly coerce compatible
 * types (VARCHAR/CLOB → TEXT, INT/BIGINT → INTEGER, etc.) on insert.
 */
private fun tryInsert(target: Connection, source: Path, reference: TableSchema): Boolean {
    val alias = "src"
    val savepoint = "merge_file"
    val srcLiteral = source.absolutePathString().replace("'", "''")
    target.createStatement().use { it.execute("ATTACH DATABASE '$srcLiteral' AS $alias") }
    target.createStatement().use { it.execute("SAVEPOINT $savepoint") }

    val rowsPerTable = LinkedHashMap<String, Int>()
    var failure: SQLException? = null

    try {
        for (table in MERGE_TABLE_ORDER) {
            val colList = reference.getValue(table).joinToString(", ") { "\"$it\"" }
            val whereClause = if (table in TIME_FILTERED_TABLES) {
                " WHERE datetime(created_at) < datetime('$CUTOFF')"
            } else {
                ""
            }
            target.createStatement().use { st ->
                rowsPerTable[table] = st.executeUpdate(
                    "INSERT INTO main.\"$table\" ($colList) " +
                            "SELECT $colList FROM $alias.\"$table\"$whereClause",
                )
            }
        }
        pruneDanglingProposals(target)
    } catch (e: SQLException) {
        failure = e
    }

    if (failure != null) {
        target.createStatement().use { it.execute("ROLLBACK TO SAVEPOINT $savepoint") }
    }
    target.createStatement().use { it.execute("RELEASE SAVEPOINT $savepoint") }
    target.commit() // commit must happen before DETACH so SQLite releases the lock on src
    target.createStatement().use { it.execute("DETACH DATABASE $alias") }

    if (failure != null) {
        log.error("Skipping {}: insert failed ({})", source.name, failure.message)
        return false
    }
    for ((t, n) in rowsPerTable) log.info("  {} {}: +{} rows", source.name, t, n)
    log.info("Merged {}", source.name)
    return true
}

/**
 * Deletes any Proposal rows that, after the created_at cutoff filter, have no
 * surviving ProposalRevision AND no surviving ProposalStatus rows. Child rows
 * that reference such Proposals (RelatedProposal, Comment)
 * are removed first so the Proposal delete doesn't violate FKs.
 */
private fun pruneDanglingProposals(target: Connection) {
    // (project_id, proposal_id) pairs that have NO revisions and NO statuses left.
    @Language("SQL")
    val danglingCte = """
            WITH dangling AS (
                SELECT p.project_id, p.proposal_id
                FROM Proposal p
                WHERE NOT EXISTS (
                    SELECT 1 FROM ProposalRevision r
                    WHERE r.project_id = p.project_id AND r.proposal_id = p.proposal_id
                ) AND NOT EXISTS (
                    SELECT 1 FROM ProposalStatus s
                    WHERE s.project_id = p.project_id AND s.proposal_id = p.proposal_id
                )
            )
        """.trimIndent()

    val totalProposalsDeleted = target.createStatement().use { st ->
        st.executeUpdate("$danglingCte DELETE FROM ProposalRevisionAuthor WHERE (project_id, proposal_id) IN (SELECT project_id, proposal_id FROM dangling)")
        st.executeUpdate("$danglingCte DELETE FROM Proposal WHERE (project_id, proposal_id) IN (SELECT project_id, proposal_id FROM dangling)")
    }

    if (totalProposalsDeleted > 0) {
        log.info(
            "  pruned {} dangling rows (proposals with no revisions/statuses after {} cutoff)",
            totalProposalsDeleted,
            CUTOFF
        )
    }
}
