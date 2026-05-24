package dev.cse3000.gh.git

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class RevisionWalkerTest {

    @TempDir
    lateinit var tempRoot: Path
    private lateinit var workTree: Path

    @BeforeEach
    fun setUp() {
        // RepoMirror's gitDir is `<rootDir>/<owner>_<repo>.git`; we initialize the
        // repo there as a non-bare working tree so the test can use plain file IO
        // to compose commits. JGit's `Git.open` handles both shapes.
        workTree = tempRoot.resolve("test_repo.git")
        Files.createDirectories(workTree)
        Git.init().setDirectory(workTree.toFile()).call().close()
    }

    @AfterEach
    fun tearDown() {
        // @TempDir handles cleanup; nothing else to do.
    }

    @Test
    fun `walks renamed file and emits pre-rename history`() = runTest {
        commitFile("proposals/KEEP-0001-foo.md", "v1 body", message = "C1 add", seconds = 1)
        commitFile("proposals/KEEP-0001-foo.md", "v2 body — slightly longer", message = "C2 edit", seconds = 2)
        renameAndCommit(
            from = "proposals/KEEP-0001-foo.md",
            to = "proposals/KEEP-0001-bar.md",
            message = "C3 rename",
            seconds = 3,
        )
        commitFile("proposals/KEEP-0001-bar.md", "v3 body — final words", message = "C4 edit", seconds = 4)

        val rows = walkAll()
        val byTime = rows.sortedBy { it["committed_at"]!!.string() }

        assertThat(rows)
            .describedAs("expected 4 rows across the full history")
            .hasSize(4)
            .allSatisfy { row -> assertThat(row["head_path"]?.string()).isEqualTo("proposals/KEEP-0001-bar.md") }

        assertThat(byTime[0]["content_text"]!!.string()).isEqualTo("v1 body")
        assertThat(byTime[0]["path"]!!.string()).isEqualTo("proposals/KEEP-0001-foo.md")
        assertThat(byTime[1]["content_text"]!!.string()).isEqualTo("v2 body — slightly longer")
        assertThat(byTime[1]["path"]!!.string()).isEqualTo("proposals/KEEP-0001-foo.md")

        // C3 is the rename commit; the renamed_from witness must appear on or after
        // it (exact attachment depends on JGit's callback timing).
        assertThat(byTime.drop(2))
            .describedAs("expected a renamed_from witness on the rename commit or its successors")
            .anySatisfy { row ->
                assertThat(row["renamed_from"]?.string()).isEqualTo("proposals/KEEP-0001-foo.md")
            }

        assertThat(byTime[3]["content_text"]!!.string()).isEqualTo("v3 body — final words")
        assertThat(byTime[3]["path"]!!.string()).isEqualTo("proposals/KEEP-0001-bar.md")
    }

    @Test
    fun `incremental walk crossing rename does not duplicate`() = runTest {
        commitFile("proposals/KEEP-0001-foo.md", "v1", message = "C1", seconds = 1)
        commitFile("proposals/KEEP-0001-foo.md", "v2", message = "C2", seconds = 2)
        renameAndCommit(
            from = "proposals/KEEP-0001-foo.md",
            to = "proposals/KEEP-0001-bar.md",
            message = "C3 rename",
            seconds = 3,
        )
        val firstWalk = walkAll()
        assertThat(firstWalk).describedAs("first walk should emit one row per commit (C1, C2, C3)").hasSize(3)
        val headAfterC3 = headSha()

        commitFile("proposals/KEEP-0001-bar.md", "v4", message = "C4", seconds = 4)
        val incrementalRows = walkAll(sinceSha = headAfterC3)

        assertThat(incrementalRows).describedAs("only C4 should be re-emitted").hasSize(1)
        val only = incrementalRows.single()
        assertThat(only["head_path"]!!.string()).isEqualTo("proposals/KEEP-0001-bar.md")
        assertThat(only["path"]!!.string()).isEqualTo("proposals/KEEP-0001-bar.md")
        assertThat(only["renamed_from"]).describedAs("C4 is not a rename event").isNull()
    }

    @Test
    fun `linear history without renames behaves like a non-following walk`() = runTest {
        commitFile("proposals/KEEP-0002-baz.md", "first body", message = "C1", seconds = 1)
        commitFile("proposals/KEEP-0002-baz.md", "second body", message = "C2", seconds = 2)
        commitFile("proposals/KEEP-0002-baz.md", "third body", message = "C3", seconds = 3)

        val rows = walkAll().sortedBy { it["committed_at"]!!.string() }

        assertThat(rows).hasSize(3)
        assertThat(rows).allSatisfy { row ->
            assertThat(row["head_path"]!!.string()).isEqualTo("proposals/KEEP-0002-baz.md")
            assertThat(row["path"]!!.string()).isEqualTo("proposals/KEEP-0002-baz.md")
            assertThat(row["renamed_from"]).isNull()
        }
        assertThat(rows.map { it["content_text"]!!.string() })
            .containsExactly("first body", "second body", "third body")
    }

    @Test
    fun `deleted-at-HEAD paths are not walked`() = runTest {
        commitFile("proposals/KEEP-0003-doomed.md", "born", message = "C1", seconds = 1)
        commitFile("proposals/KEEP-0003-doomed.md", "edited", message = "C2", seconds = 2)
        // Delete the file.
        Files.delete(workTree.resolve("proposals/KEEP-0003-doomed.md"))
        Git.open(workTree.toFile()).use { git ->
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit()
                .setSign(false)
                .setAuthor(authorAt(3))
                .setCommitter(authorAt(3))
                .setMessage("C3 delete")
                .call()
        }
        // Add an unrelated file so HEAD has *something* matching the predicate (otherwise nothing
        // is enumerated at HEAD and the test trivially passes).
        commitFile("proposals/KEEP-0004-other.md", "other body", message = "C4 unrelated", seconds = 4)

        val rows = walkAll()
        assertThat(rows.map { it["head_path"]!!.string() }.toSet())
            .describedAs("deleted proposal should not appear in the walk")
            .containsOnly("proposals/KEEP-0004-other.md")
    }

    private suspend fun walkAll(sinceSha: String? = null): List<JsonObject> {
        return RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot).use {
            val walker = RevisionWalker(it) { path ->
                path.startsWith("proposals/") && path.endsWith(".md")
            }
            walker.walk(sinceSha).toList()
        }
    }

    private fun headSha(): String =
        RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot).use { mirror ->
            RevisionWalker(mirror) { true }.headSha()!!
        }

    private fun commitFile(relPath: String, content: String, message: String, seconds: Long) {
        val file = workTree.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        Git.open(workTree.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.commit()
                .setSign(false)
                .setAuthor(authorAt(seconds))
                .setCommitter(authorAt(seconds))
                .setMessage(message)
                .call()
        }
    }

    private fun renameAndCommit(from: String, to: String, message: String, seconds: Long) {
        val src = workTree.resolve(from)
        val dst = workTree.resolve(to)
        Files.createDirectories(dst.parent)
        // Preserve content across the rename so JGit's rename detector scores the
        // similarity at 100% — mirrors the real `git mv` operation.
        Files.move(src, dst)
        Git.open(workTree.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.rm().addFilepattern(from).call()
            git.commit()
                .setSign(false)
                .setAuthor(authorAt(seconds))
                .setCommitter(authorAt(seconds))
                .setMessage(message)
                .call()
        }
    }

    private fun authorAt(seconds: Long): PersonIdent =
        PersonIdent("Test Author", "test@example.com", Instant.ofEpochSecond(seconds), ZoneId.of("UTC"))

    private fun JsonElement.string(): String = (this as JsonPrimitive).content
}
