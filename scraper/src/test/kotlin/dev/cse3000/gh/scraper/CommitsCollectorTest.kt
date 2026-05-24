package dev.cse3000.gh.scraper

import dev.cse3000.gh.cache.SeenShas
import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.io.JsonlSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class CommitsCollectorTest {

    @TempDir
    lateinit var tempRoot: Path

    @TempDir
    lateinit var sinkDir: Path

    private lateinit var workTree: Path
    private lateinit var seenShasPath: Path

    @BeforeEach
    fun setUp() {
        workTree = tempRoot.resolve("test_repo.git")
        Files.createDirectories(workTree)
        Git.init().setDirectory(workTree.toFile()).call().close()
        seenShasPath = tempRoot.resolve("seen-commit-shas.json")
    }

    @Test
    fun `first run fetches every reachable sha, second run fetches only the new one`() = runTest {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)

        val fetches1 = mutableListOf<String>()
        runCollector(fetches1)
        assertThat(fetches1).containsExactlyInAnyOrder(c1, c2)
        assertThat(readSeenShas()).containsExactlyInAnyOrder(c1, c2)
        assertThat(readEmittedShas()).hasSize(2)

        val c3 = commit("a.md", "v3", seconds = 3)

        val fetches2 = mutableListOf<String>()
        runCollector(fetches2)
        assertThat(fetches2)
            .describedAs("second run should fetch only the new SHA")
            .containsExactly(c3)
        assertThat(readSeenShas()).containsExactlyInAnyOrder(c1, c2, c3)
        assertThat(readEmittedShas()).hasSize(3)
    }

    @Test
    fun `back-merged commit with old committer date is still fetched`() = runTest {
        val c1 = commit("a.md", "old", seconds = 1000)
        val c2 = commit("a.md", "newer", seconds = 2000)
        runCollector(mutableListOf())

        // A back-merge: a brand-new commit C3 whose committer date is older than
        // anything we've already seen. A `?since=` cursor would skip this; the
        // SHA-set approach must not.
        val c3 = commit("b.md", "back-merged content", seconds = 100)

        val fetches = mutableListOf<String>()
        runCollector(fetches)
        assertThat(fetches).containsExactly(c3)
        assertThat(readSeenShas()).contains(c1, c2, c3)
    }

    @Test
    fun `force-pushed-away SHAs stay in the seen set and new tip is fetched`() = runTest {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)
        val c3 = commit("a.md", "v3", seconds = 3)
        runCollector(mutableListOf())

        // Simulate force-push: reset HEAD to c1, then add two fresh commits over it.
        Git.open(workTree.toFile()).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(c1).call()
        }
        val c2p = commit("a.md", "v2-prime", seconds = 10)
        val c3p = commit("a.md", "v3-prime", seconds = 11)

        val fetches = mutableListOf<String>()
        runCollector(fetches)
        // The new tip's commits should be fetched. Orphaned c2/c3 stay in the seen
        // set so we don't re-fetch them, even though they're no longer reachable.
        // c1 is still reachable but already seen, so it's not re-fetched.
        assertThat(fetches).contains(c2p, c3p).doesNotContain(c1, c2, c3)
        assertThat(readSeenShas())
            .describedAs("orphaned SHAs are retained, new tips are added")
            .contains(c1, c2, c3, c2p, c3p)
    }

    @Test
    fun `limit caps the number of new commits fetched in one run`() = runTest {
        val shas = (1..5L).map { commit("a.md", "v$it", seconds = it) }
        val fetches = mutableListOf<String>()
        runCollector(fetches, limit = 3)
        assertThat(fetches).describedAs("limit=3 should cap fetched commits").hasSize(3)
        // The 2 deferred SHAs remain absent from the seen set so the next run picks them up.
        assertThat(readSeenShas()).hasSize(3).containsExactlyInAnyOrderElementsOf(fetches)

        val followUp = mutableListOf<String>()
        runCollector(followUp)
        assertThat(followUp).describedAs("second run should pick up the deferred SHAs").hasSize(2)
        assertThat(readSeenShas()).containsExactlyInAnyOrderElementsOf(shas)
    }

    @Test
    fun `non-incremental run ignores the seen set and re-fetches everything`() = runTest {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)
        runCollector(mutableListOf())
        assertThat(readSeenShas()).containsExactlyInAnyOrder(c1, c2)

        val fetches = mutableListOf<String>()
        runCollector(fetches, incremental = false)
        assertThat(fetches)
            .describedAs("incremental=false should re-fetch every reachable SHA regardless of seen set")
            .containsExactlyInAnyOrder(c1, c2)
    }

    @Test
    fun `null fetch result skips emission and seen-set update`() = runTest {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)

        var refusal = true
        val mirror = RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot)
        val seenShas = SeenShas(seenShasPath)
        JsonlSink(sinkDir).use { sink ->
            mirror.use {
                CommitsCollector(
                    sink = sink,
                    mirror = it,
                    repoTag = "test",
                    seenShas = seenShas,
                    concurrency = 1,
                    rateLimitSnapshot = { -1 },
                    fetchCommit = { sha -> if (refusal) null else fakeFetched(sha) },
                ).run(incremental = true, limit = null)
            }
        }
        seenShas.persist()
        assertThat(readSeenShas()).describedAs("nothing should land in the seen set on null returns").isEmpty()
        assertThat(readEmittedShas()).isEmpty()

        // Second run with the same fixture but now returning data — both SHAs come through.
        refusal = false
        val fetches = mutableListOf<String>()
        runCollector(fetches)
        assertThat(fetches).containsExactlyInAnyOrder(c1, c2)
    }

    private suspend fun runCollector(
        recordFetches: MutableList<String>,
        limit: Int? = null,
        incremental: Boolean = true,
    ) {
        val mirror = RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot)
        val seenShas = SeenShas(seenShasPath)
        JsonlSink(sinkDir).use { sink ->
            mirror.use {
                CommitsCollector(
                    sink = sink,
                    mirror = it,
                    repoTag = "test",
                    seenShas = seenShas,
                    concurrency = 4,
                    rateLimitSnapshot = { -1 },
                    fetchCommit = { sha ->
                        recordFetches += sha
                        fakeFetched(sha)
                    },
                ).run(incremental, limit)
            }
        }
        seenShas.persist()
    }

    private fun fakeFetched(sha: String): JsonObject = buildJsonObject {
        put("sha", JsonPrimitive(sha))
        put(
            "commit",
            buildJsonObject {
                put(
                    "author",
                    buildJsonObject {
                        put("name", JsonPrimitive("Test Author"))
                        put("email", JsonPrimitive("test@example.com"))
                    },
                )
            },
        )
        put("author", buildJsonObject { put("login", JsonPrimitive("testuser")) })
    }

    private suspend fun readSeenShas(): Set<String> = SeenShas(seenShasPath).get("test/repo")

    private fun readEmittedShas(): List<String> {
        val file = sinkDir.resolve("test-commits.jsonl")
        if (!Files.exists(file)) return emptyList()
        val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
            val obj = parser.parseToJsonElement(line) as JsonObject
            (obj["sha"] as JsonPrimitive).content
        }
    }

    private fun commit(relPath: String, content: String, seconds: Long): String {
        val file = workTree.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return Git.open(workTree.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            val ident = PersonIdent(
                "Test Author", "test@example.com",
                Instant.ofEpochSecond(seconds),
                ZoneId.of("UTC"),
            )
            git.commit()
                .setSign(false)
                .setAuthor(ident)
                .setCommitter(ident)
                .setMessage("commit at $seconds")
                .call()
                .id.name
        }
    }
}
