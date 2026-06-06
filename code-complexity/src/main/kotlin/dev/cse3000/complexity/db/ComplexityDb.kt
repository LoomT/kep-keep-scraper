package dev.cse3000.complexity.db

import dev.cse3000.complexity.analyzer.FileResult
import dev.cse3000.complexity.analyzer.FunctionResult
import dev.cse3000.complexity.config.ProjectConfig
import dev.cse3000.complexity.git.SnapshotInfo
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

private val SCHEMA = """
CREATE TABLE IF NOT EXISTS Project (
    project_id  INTEGER PRIMARY KEY,
    owner_repo  TEXT NOT NULL UNIQUE,
    subfolder   TEXT
);

CREATE TABLE IF NOT EXISTS Snapshot (
    snapshot_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    INTEGER NOT NULL REFERENCES Project(project_id),
    quarter_label TEXT    NOT NULL,
    commit_sha    TEXT    NOT NULL,
    commit_time   TEXT    NOT NULL,
    analyzed_at   TEXT    NOT NULL,
    UNIQUE(project_id, commit_sha)
);

CREATE TABLE IF NOT EXISTS FunctionMetric (
    snapshot_id           INTEGER NOT NULL REFERENCES Snapshot(snapshot_id),
    file_path             TEXT    NOT NULL,
    function_name         TEXT    NOT NULL,
    start_line            INTEGER NOT NULL,
    end_line              INTEGER NOT NULL,
    nloc                  INTEGER NOT NULL,
    cyclomatic_complexity INTEGER NOT NULL,
    token_count           INTEGER NOT NULL,
    parameter_count       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS FileMetric (
    snapshot_id   INTEGER NOT NULL REFERENCES Snapshot(snapshot_id),
    file_path     TEXT    NOT NULL,
    language      TEXT    NOT NULL,
    lines         INTEGER NOT NULL,
    code_lines    INTEGER NOT NULL,
    comment_lines INTEGER NOT NULL,
    blank_lines   INTEGER NOT NULL,
    complexity    INTEGER NOT NULL,
    bytes         INTEGER NOT NULL,
    PRIMARY KEY (snapshot_id, file_path)
);

CREATE INDEX IF NOT EXISTS idx_func_snapshot   ON FunctionMetric(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_file_snapshot   ON FileMetric(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_snapshot_project ON Snapshot(project_id);
""".trimIndent()

class ComplexityDb(path: Path) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        .also { it.autoCommit = false }

    fun applySchema() {
        conn.createStatement().use { st ->
            for (stmt in SCHEMA.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                st.execute(stmt)
            }
        }
        conn.commit()
    }

    fun upsertProject(config: ProjectConfig) {
        conn.prepareStatement("INSERT OR IGNORE INTO Project (project_id, owner_repo, subfolder) VALUES (?, ?, ?)")
            .use { ps ->
                ps.setInt(1, config.projectId)
                ps.setString(2, config.ownerRepo)
                ps.setString(3, config.subfolder)
                ps.executeUpdate()
            }
    }

    fun snapshotExists(projectId: Int, commitSha: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM Snapshot WHERE project_id = ? AND commit_sha = ?").use { ps ->
            ps.setInt(1, projectId)
            ps.setString(2, commitSha)
            return ps.executeQuery().next()
        }
    }

    fun insertSnapshot(projectId: Int, snap: SnapshotInfo): Int {
        conn.prepareStatement(
            "INSERT INTO Snapshot (project_id, quarter_label, commit_sha, commit_time, analyzed_at) VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setInt(1, projectId)
            ps.setString(2, snap.label)
            ps.setString(3, snap.commitSha)
            ps.setString(4, snap.commitTime.toString())
            ps.setString(5, Instant.now().toString())
            ps.executeUpdate()
        }
        return conn.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }.also { conn.commit() }
    }

    fun insertFunctionMetrics(snapshotId: Int, functions: List<FunctionResult>) {
        if (functions.isEmpty()) return
        conn.prepareStatement(
            """INSERT INTO FunctionMetric
               (snapshot_id, file_path, function_name, start_line, end_line,
                nloc, cyclomatic_complexity, token_count, parameter_count)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { ps ->
            for (f in functions) {
                ps.setInt(1, snapshotId)
                ps.setString(2, f.filePath)
                ps.setString(3, f.functionName)
                ps.setInt(4, f.startLine)
                ps.setInt(5, f.endLine)
                ps.setInt(6, f.nloc)
                ps.setInt(7, f.cyclomaticComplexity)
                ps.setInt(8, f.tokenCount)
                ps.setInt(9, f.parameterCount)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
    }

    fun insertFileMetrics(snapshotId: Int, files: List<FileResult>) {
        if (files.isEmpty()) return
        conn.prepareStatement(
            """INSERT INTO FileMetric
               (snapshot_id, file_path, language, lines, code_lines, comment_lines,
                blank_lines, complexity, bytes)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { ps ->
            for (f in files) {
                ps.setInt(1, snapshotId)
                ps.setString(2, f.filePath)
                ps.setString(3, f.language)
                ps.setInt(4, f.lines)
                ps.setInt(5, f.codeLines)
                ps.setInt(6, f.commentLines)
                ps.setInt(7, f.blankLines)
                ps.setInt(8, f.complexity)
                ps.setLong(9, f.bytes)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
    }

    override fun close() = conn.close()
}
