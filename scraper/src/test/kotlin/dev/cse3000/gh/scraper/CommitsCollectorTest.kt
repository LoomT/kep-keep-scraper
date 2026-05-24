package dev.cse3000.gh.scraper

import dev.cse3000.gh.cache.SeenShas
import dev.cse3000.gh.git.RepoMirror
import dev.cse3000.gh.io.JsonlSink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class CommitsCollectorTest {

    private lateinit var tempRoot: Path
    private lateinit var workTree: Path
    private lateinit var sinkDir: Path
    private lateinit var seenShasPath: Path

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("commitscollector-test-")
        workTree = tempRoot.resolve("test_repo.git")
        Files.createDirectories(workTree)
        Git.init().setDirectory(workTree.toFile()).call().close()
        sinkDir = Files.createTempDirectory("commitscollector-sink-")
        seenShasPath = tempRoot.resolve("seen-commit-shas.json")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
        sinkDir.toFile().deleteRecursively()
    }

    @Test
    fun `first run fetches every reachable sha, second run fetches only the new one`() {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)

        val fetches1 = mutableListOf<String>()
        runCollector(fetches1)
        assertEquals(setOf(c1, c2), fetches1.toSet())
        assertEquals(setOf(c1, c2), readSeenShas())
        assertEquals(2, readEmittedShas().size)

        val c3 = commit("a.md", "v3", seconds = 3)

        val fetches2 = mutableListOf<String>()
        runCollector(fetches2)
        assertEquals(listOf(c3), fetches2, "second run should fetch only the new SHA")
        assertEquals(setOf(c1, c2, c3), readSeenShas())
        assertEquals(3, readEmittedShas().size)
    }

    @Test
    fun `back-merged commit with old committer date is still fetched`() {
        val c1 = commit("a.md", "old", seconds = 1000)
        val c2 = commit("a.md", "newer", seconds = 2000)
        val fetches1 = mutableListOf<String>()
        runCollector(fetches1)
        assertEquals(setOf(c1, c2), fetches1.toSet())

        // A back-merge: a brand-new commit C3 whose committer date is older than
        // anything we've already seen. A `?since=` cursor would skip this; the
        // SHA-set approach must not.
        val c3 = commit("b.md", "back-merged content", seconds = 100)

        val fetches2 = mutableListOf<String>()
        runCollector(fetches2)
        assertEquals(listOf(c3), fetches2)
        assertTrue(c3 in readSeenShas())
    }

    @Test
    fun `force-pushed-away SHAs stay in the seen set and new tip is fetched`() {
        val c1 = commit("a.md", "v1", seconds = 1)
        val c2 = commit("a.md", "v2", seconds = 2)
        val c3 = commit("a.md", "v3", seconds = 3)
        val firstFetches = mutableListOf<String>()
        runCollector(firstFetches)
        assertEquals(setOf(c1, c2, c3), firstFetches.toSet())

        // Simulate force-push: reset HEAD to c1, then add two fresh commits over it.
        Git.open(workTree.toFile()).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(c1).call()
        }
        val c2p = commit("a.md", "v2-prime", seconds = 10)
        val c3p = commit("a.md", "v3-prime", seconds = 11)

        val secondFetches = mutableListOf<String>()
        runCollector(secondFetches)
        // The new tip's commits should be fetched. Orphaned c2/c3 stay in the seen
        // set so we don't re-fetch them, even though they're no longer reachable.
        // c1 is still reachable but already seen, so it's not re-fetched.
        assertTrue(c2p in secondFetches.toSet())
        assertTrue(c3p in secondFetches.toSet())
        assertTrue(c2 !in secondFetches)
        assertTrue(c3 !in secondFetches)
        val seen = readSeenShas()
        assertTrue(
            seen.containsAll(setOf(c1, c2, c3, c2p, c3p)),
            "seen set should retain orphans (c2,c3) and include new tips (c2p,c3p); got $seen",
        )
    }

    @Test
    fun `limit caps the number of new commits fetched in one run`() {
        val shas = (1..5L).map { commit("a.md", "v$it", seconds = it) }
        val fetches = mutableListOf<String>()
        runCollector(fetches, limit = 3)
        assertEquals(3, fetches.size, "limit=3 should cap fetched commits")
        // The 2 deferred SHAs remain absent from the seen set, so the next run picks them up.
        val seenAfterFirst = readSeenShas()
        assertEquals(3, seenAfterFirst.size)
        assertEquals(seenAfterFirst, fetches.toSet())

        val followUp = mutableListOf<String>()
        runCollector(followUp)
        assertEquals(2, followUp.size, "second run should pick up the 2 deferred SHAs")
        assertEquals(shas.toSet(), readSeenShas())
    }

    private fun runCollector(recordFetches: MutableList<String>, limit: Int? = null) {
        val mirror = RepoMirror(owner = "test", repo = "repo", rootDir = tempRoot)
        val seenShas = SeenShas(seenShasPath)
        runBlocking {
            JsonlSink(sinkDir).use { sink ->
                mirror.use {
                    val collector = CommitsCollector(
                        sink = sink,
                        mirror = it,
                        repoTag = "test",
                        seenShas = seenShas,
                        concurrency = 4,
                        rateLimitSnapshot = { -1 },
                        fetchCommit = { sha ->
                            recordFetches += sha
                            buildJsonObject {
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
                                put(
                                    "author",
                                    buildJsonObject { put("login", JsonPrimitive("testuser")) },
                                )
                            }
                        },
                    )
                    collector.run(true, limit)
                }
            }
            seenShas.persist()
        }
    }

    private fun readSeenShas(): Set<String> = runBlocking {
        SeenShas(seenShasPath).get("test/repo")
    }

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
