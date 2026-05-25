package dev.cse3000.loader

import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JsonlSourceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `readJsonlObjects returns an empty sequence when the file is missing`() {
        val result = readJsonlObjects(tempDir, "does-not-exist").toList()
        assertThat(result).isEmpty()
    }

    @Test
    fun `readJsonlObjects yields one JsonObject per non-blank line`() {
        writeJsonl(
            "stream",
            """{"a":1,"_scraped_at":"2026-01-01T00:00:00Z"}""",
            "", // blank line — skipped
            """{"a":2,"_scraped_at":"2026-01-02T00:00:00Z"}""",
        )
        val rows = readJsonlObjects(tempDir, "stream").toList()
        assertThat(rows).hasSize(2)
        assertThat(rows[0]["a"]).isEqualTo(JsonPrimitive(1))
        assertThat(rows[1]["a"]).isEqualTo(JsonPrimitive(2))
    }

    @Test
    fun `readJsonlObjects skips invalid lines but yields the rest`() {
        writeJsonl(
            "stream",
            """{"a":1,"_scraped_at":"2026-01-01T00:00:00Z"}""",
            "not json at all",
            """[ "an array", "is not an object" ]""", // valid JSON but not a JsonObject — skipped
            """{"a":2,"_scraped_at":"2026-01-02T00:00:00Z"}""",
        )
        val rows = readJsonlObjects(tempDir, "stream").toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { (it["a"] as JsonPrimitive).int }).containsExactly(1, 2)
    }

    @Test
    fun `keepLatestScrapesBy keeps the row with the latest scraped_at per selector`() {
        writeJsonl(
            "stream",
            """{"id":"a","payload":"v1","_scraped_at":"2026-01-01T00:00:00Z"}""",
            """{"id":"a","payload":"v2","_scraped_at":"2026-01-02T00:00:00Z"}""",
            """{"id":"a","payload":"v0","_scraped_at":"2025-12-01T00:00:00Z"}""",
            """{"id":"b","payload":"only","_scraped_at":"2026-01-03T00:00:00Z"}""",
        )
        val rows = readJsonlObjects(tempDir, "stream")
            .keepLatestScrapesBy { (it["id"] as JsonPrimitive).content }
            .toList()
        val byId = rows.associate { (it["id"] as JsonPrimitive).content to (it["payload"] as JsonPrimitive).content }
        assertThat(byId).containsExactlyInAnyOrderEntriesOf(mapOf("a" to "v2", "b" to "only"))
    }

    private fun writeJsonl(stream: String, vararg lines: String) {
        Files.writeString(tempDir.resolve("$stream.jsonl"), lines.joinToString("\n"))
    }

    private val JsonPrimitive.int: Int get() = content.toInt()
}
