package dev.cse3000.gh.git

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GitCursorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `get returns null when the slug is unknown`() = runTest {
        val cursor = GitCursor(tempDir.resolve("git-heads.json"))
        assertThat(cursor.get("anything")).isNull()
    }

    @Test
    fun `set overrides previous values`() = runTest {
        val cursor = GitCursor(tempDir.resolve("git-heads.json"))
        cursor.set("a/r", "abc123")
        cursor.set("a/r", "def456")
        assertThat(cursor.get("a/r"))
            .describedAs("git head cursor is overwritten on each successful walk — not append-only")
            .isEqualTo("def456")
    }

    @Test
    fun `persist and reload preserves all entries`() = runTest {
        val path = tempDir.resolve("git-heads.json")
        GitCursor(path).apply {
            set("a/r", "sha1")
            set("b/r", "sha2")
            persist()
        }
        val reloaded = GitCursor(path)
        assertThat(reloaded.get("a/r")).isEqualTo("sha1")
        assertThat(reloaded.get("b/r")).isEqualTo("sha2")
    }

    @Test
    fun `missing file loads as empty`() = runTest {
        val cursor = GitCursor(tempDir.resolve("missing.json"))
        assertThat(cursor.get("anything")).isNull()
    }

    @Test
    fun `blank file loads as empty`() = runTest {
        val path = tempDir.resolve("blank.json")
        Files.writeString(path, "\n")
        val cursor = GitCursor(path)
        assertThat(cursor.get("anything")).isNull()
    }
}
