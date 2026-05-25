package dev.cse3000.gh.cache

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SyncCursorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `get returns null for an unknown key`() = runTest {
        val cursor = SyncCursor(tempDir.resolve("cursor.json"))
        assertThat(cursor.get("missing")).isNull()
    }

    @Test
    fun `advance moves the cursor forward but not backward`() = runTest {
        val cursor = SyncCursor(tempDir.resolve("cursor.json"))
        cursor.advance("k", "2026-01-01T00:00:00Z")
        cursor.advance("k", "2026-01-02T00:00:00Z")
        assertThat(cursor.get("k")).isEqualTo("2026-01-02T00:00:00Z")

        cursor.advance("k", "2025-12-31T00:00:00Z")
        assertThat(cursor.get("k"))
            .describedAs("older timestamp must not roll the cursor back")
            .isEqualTo("2026-01-02T00:00:00Z")
    }

    @Test
    fun `advance with the same value is a no-op`() = runTest {
        val cursor = SyncCursor(tempDir.resolve("cursor.json"))
        cursor.advance("k", "2026-01-01T00:00:00Z")
        cursor.advance("k", "2026-01-01T00:00:00Z")
        assertThat(cursor.get("k")).isEqualTo("2026-01-01T00:00:00Z")
    }

    @Test
    fun `persist and reload preserves all entries`() = runTest {
        val path = tempDir.resolve("cursor.json")
        SyncCursor(path).apply {
            advance("a", "2026-01-01T00:00:00Z")
            advance("b", "2026-02-01T00:00:00Z")
            persist()
        }
        val reloaded = SyncCursor(path)
        assertThat(reloaded.get("a")).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(reloaded.get("b")).isEqualTo("2026-02-01T00:00:00Z")
    }

    @Test
    fun `missing file loads as empty`() = runTest {
        val cursor = SyncCursor(tempDir.resolve("missing.json"))
        assertThat(cursor.get("anything")).isNull()
    }

    @Test
    fun `blank file loads as empty`() = runTest {
        val path = tempDir.resolve("blank.json")
        Files.writeString(path, "")
        val cursor = SyncCursor(path)
        assertThat(cursor.get("anything")).isNull()
    }
}
