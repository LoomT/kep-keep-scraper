package dev.cse3000.gh.git

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class RevisionWalkerTest {

    private lateinit var tempRoot: Path
    private lateinit var workTree: Path

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("revwalker-test-")
        // RepoMirror's gitDir is `<rootDir>/<owner>_<repo>.git`; we initialize the
        // repo there as a non-bare working tree so the test can use plain file IO
        // to compose commits. JGit's `Git.open` handles both shapes.
        workTree = tempRoot.resolve("test_repo.git")
        Files.createDirectories(workTree)
        Git.init().setDirectory(workTree.toFile()).call().close()
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `walks renamed file and emits pre-rename history`() {
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

        assertEquals(4, rows.size, "expected 4 rows across the full history")
        assertTrue(
            rows.all { it["head_path"]?.string() == "proposals/KEEP-0001-bar.md" },
            "every row should be keyed on the HEAD path; got ${rows.map { it["head_path"]?.string() }}",
        )

        val byTime = rows.sortedBy { it["committed_at"]!!.string() }
        assertEquals("v1 body", byTime[0]["content_text"]!!.string())
        assertEquals("proposals/KEEP-0001-foo.md", byTime[0]["path"]!!.string())
        assertEquals("v2 body — slightly longer", byTime[1]["content_text"]!!.string())
        assertEquals("proposals/KEEP-0001-foo.md", byTime[1]["path"]!!.string())
        // C3 is the rename commit; some renamed_from witness must appear on or
        // after it in the walk (exact attachment depends on JGit's callback timing).
        assertTrue(
            byTime.drop(2).any { it["renamed_from"]?.string() == "proposals/KEEP-0001-foo.md" },
            "expected a renamed_from witness on the rename commit or its successors; got rows=$byTime",
        )
        assertEquals("v3 body — final words", byTime[3]["content_text"]!!.string())
        assertEquals("proposals/KEEP-0001-bar.md", byTime[3]["path"]!!.string())
    }

    @Test
    fun `incremental walk crossing rename does not duplicate`() {
        commitFile("proposals/KEEP-0001-foo.md", "v1", message = "C1", seconds = 1)
        commitFile("proposals/KEEP-0001-foo.md", "v2", message = "C2", seconds = 2)
        renameAndCommit(
            from = "proposals/KEEP-0001-foo.md",
            to = "proposals/KEEP-0001-bar.md",
            message = "C3 rename",
            seconds = 3,
        )
        val firstWalk = walkAll()
        assertEquals(3, firstWalk.size, "first walk should emit one row per commit (C1, C2, C3)")
        val headAfterC3 = headSha()

        commitFile("proposals/KEEP-0001-bar.md", "v4", message = "C4", seconds = 4)
        val incrementalRows = walkAll(sinceSha = headAfterC3)

        assertEquals(1, incrementalRows.size, "only C4 should be re-emitted")
        assertEquals("proposals/KEEP-0001-bar.md", incrementalRows.single()["head_path"]!!.string())
        assertEquals("proposals/KEEP-0001-bar.md", incrementalRows.single()["path"]!!.string())
        assertNull(incrementalRows.single()["renamed_from"], "C4 is not a rename event")
    }

    @Test
    fun `linear history without renames behaves like a non-following walk`() {
        commitFile("proposals/KEEP-0002-baz.md", "first body", message = "C1", seconds = 1)
        commitFile("proposals/KEEP-0002-baz.md", "second body", message = "C2", seconds = 2)
        commitFile("proposals/KEEP-0002-baz.md", "third body", message = "C3", seconds = 3)

        val rows = walkAll().sortedBy { it["committed_at"]!!.string() }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it["head_path"]!!.string() == "proposals/KEEP-0002-baz.md" })
        assertTrue(rows.all { it["path"]!!.string() == "proposals/KEEP-0002-baz.md" })
        assertTrue(
            rows.none { it["renamed_from"] != null },
            "no renames in this repo, but rows[].renamed_from = ${rows.map { it["renamed_from"] }}",
        )
        assertEquals(listOf("first body", "second body", "third body"), rows.map { it["content_text"]!!.string() })
    }

    private fun walkAll(sinceSha: String? = null): List<JsonObject> {
        val mirror = RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot)
        return mirror.use {
            val walker = RevisionWalker(it) { path ->
                path.startsWith("proposals/") && path.endsWith(".md")
            }
            runBlocking { walker.walk(sinceSha).toList() }
        }
    }

    private fun headSha(): String {
        val mirror = RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot)
        return mirror.use { RevisionWalker(it) { true }.headSha()!! }
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

    private fun kotlinx.serialization.json.JsonElement.string(): String = (this as JsonPrimitive).content
}
