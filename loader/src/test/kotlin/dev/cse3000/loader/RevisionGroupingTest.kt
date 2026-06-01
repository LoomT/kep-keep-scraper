package dev.cse3000.loader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RevisionGroupingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `readGroupedByHeadPath dedups by head_path plus sha`() {
        writeJsonl(
            "revs",
            row(headPath = "p/a.md", sha = "1", committedAt = "2026-01-02", payload = "v2-newer"),
            row(
                headPath = "p/a.md",
                sha = "1",
                committedAt = "2026-01-02",
                payload = "v2-older",
                scrapedAt = "2025-12-31"
            ),
            row(headPath = "p/a.md", sha = "0", committedAt = "2026-01-01"),
            row(headPath = "p/b.md", sha = "9", committedAt = "2026-01-03"),
        )
        val grouped = RevisionGrouping.readGroupedByHeadPath(tempDir, "revs")
        assertThat(grouped.keys).containsExactlyInAnyOrder("p/a.md", "p/b.md")

        val aRows = grouped.getValue("p/a.md")
        assertThat(aRows).hasSize(2)

        // The duplicate (head_path=p/a.md, sha=1) collapsed to the latest scrape — the
        // newer payload survives.
        val sha1 = aRows.first { (it["commit_sha"] as JsonPrimitive).content == "1" }
        assertThat((sha1["payload"] as JsonPrimitive).content).isEqualTo("v2-newer")
    }

    @Test
    fun `readGroupedByHeadDir groups by head_dir`() {
        writeJsonl(
            "revs",
            row(
                headPath = "keps/sig-a/0001-x/README.md",
                headDir = "keps/sig-a/0001-x",
                sha = "1",
                committedAt = "2026-01-01"
            ),
            row(
                headPath = "keps/sig-a/0001-x/kep.yaml",
                headDir = "keps/sig-a/0001-x",
                sha = "1",
                committedAt = "2026-01-01"
            ),
            row(
                headPath = "keps/sig-b/0002-y/README.md",
                headDir = "keps/sig-b/0002-y",
                sha = "9",
                committedAt = "2026-01-02"
            ),
        )
        val grouped = RevisionGrouping.readGroupedByHeadDir(tempDir, "revs")
        assertThat(grouped.keys).containsExactlyInAnyOrder("keps/sig-a/0001-x", "keps/sig-b/0002-y")
        // The yaml + readme touched in the same commit dedup separately because the dedup
        // key is "head_path + sha" — both rows survive under the same head_dir bucket.
        assertThat(grouped.getValue("keps/sig-a/0001-x")).hasSize(2)
        assertThat(grouped.getValue("keps/sig-b/0002-y")).hasSize(1)
    }

    @Test
    fun `readGroupedBy honors a custom key function`() {
        writeJsonl(
            "revs",
            row(headPath = "p/KEEP-0001-foo.md", sha = "1", committedAt = "2026-01-01"),
            row(headPath = "p/KEEP-0002-bar.md", sha = "2", committedAt = "2026-01-02"),
            row(headPath = "p/KEEP-0001-baz.md", sha = "3", committedAt = "2026-01-03"),
        )
        // Custom key: strip the slug, keep only KEEP-NNNN.
        val grouped = RevisionGrouping.readGroupedBy(tempDir, "revs") { row ->
            RevisionGrouping.headPathOf(row).substringAfter('/').take(9)
        }
        assertThat(grouped.keys).containsExactlyInAnyOrder("KEEP-0001", "KEEP-0002")
        assertThat(grouped.getValue("KEEP-0001")).hasSize(2)
    }

    @Test
    fun `headPathOf and headDirOf extract their respective fields`() {
        val obj = row(headPath = "x/y/z.md", headDir = "x/y", sha = "1", committedAt = "2026-01-01")
            .let { kotlinx.serialization.json.Json.parseToJsonElement(it) as JsonObject }
        assertThat(RevisionGrouping.headPathOf(obj)).isEqualTo("x/y/z.md")
        assertThat(RevisionGrouping.headDirOf(obj)).isEqualTo("x/y")
    }

    @Test
    fun `missing stream returns an empty map`() {
        assertThat(RevisionGrouping.readGroupedByHeadPath(tempDir, "does-not-exist")).isEmpty()
    }

    @Test
    fun `readGroupedByHeadPath reverses the upstream commit order`() {
        writeJsonl(
            "revs",
            row(headPath = "a.md", sha = "0", committedAt = "2026-01-04"),
            row(headPath = "a.md", sha = "2", committedAt = "2026-01-03"),
            row(headPath = "a.md", sha = "3", committedAt = "2026-01-03"),
            row(headPath = "a.md", sha = "1", committedAt = "2026-01-02"),
        )

        val grouped = RevisionGrouping.readGroupedByHeadPath(tempDir, "revs")
        assertThat(grouped.keys).containsExactly("a.md")

        val aRows = grouped.getValue("a.md")
        assertThat(aRows).hasSize(4)
        assertThat(aRows.map { (it["commit_sha"] as JsonPrimitive).content })
            .describedAs("commit rows are reversed (upstream is in reverse chronological order)")
            .containsExactly("1", "3", "2", "0")
    }

    private fun writeJsonl(stream: String, vararg rows: String) {
        Files.writeString(tempDir.resolve("$stream.jsonl"), rows.joinToString("\n"))
    }

    private fun row(
        headPath: String,
        sha: String,
        committedAt: String,
        headDir: String = headPath.substringBeforeLast('/', ""),
        path: String = headPath,
        payload: String = "data",
        scrapedAt: String = "2026-06-01T00:00:00Z",
    ): String = buildString {
        append("""{"head_path":"""").append(headPath).append('"')
        append(""","head_dir":"""").append(headDir).append('"')
        append(""","path":"""").append(path).append('"')
        append(""","commit_sha":"""").append(sha).append('"')
        append(""","committed_at":"""").append(committedAt).append('"')
        append(""","payload":"""").append(payload).append('"')
        append(""","_scraped_at":"""").append(scrapedAt).append('"')
        append('}')
    }
}
