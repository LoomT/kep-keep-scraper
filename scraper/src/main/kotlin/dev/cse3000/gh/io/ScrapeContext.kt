package dev.cse3000.gh.io

import dev.cse3000.gh.cache.EtagStore
import dev.cse3000.gh.cache.RawCache
import dev.cse3000.gh.cache.SeenShas
import dev.cse3000.gh.cache.SyncCursor
import dev.cse3000.gh.client.GithubClient
import dev.cse3000.gh.client.RateLimiter
import dev.cse3000.gh.git.GitCursor
import java.nio.file.Path

class ScrapeContext(
    val client: GithubClient,
    val sink: JsonlSink,
    val cursor: SyncCursor,
    val etags: EtagStore,
    val gitCursor: GitCursor,
    val seenCommitShas: SeenShas,
    val dataDir: Path,
) : AutoCloseable {
    val reposDir: Path = dataDir.resolve("repos")

    suspend fun persist() {
        etags.persist()
        cursor.persist()
        gitCursor.persist()
        seenCommitShas.persist()
    }

    override fun close() {
        runCatching { sink.close() }
        runCatching { client.close() }
    }

    companion object {
        fun create(dataDir: Path = Env.dataDir()): ScrapeContext {
            val token = Env.githubToken()
            val cache = RawCache(dataDir.resolve("cache").resolve("raw"))
            val etags = EtagStore(dataDir.resolve("cache").resolve("etags.json"))
            val cursor = SyncCursor(dataDir.resolve("cache").resolve("last-sync.json"))
            val gitCursor = GitCursor(dataDir.resolve("cache").resolve("git-heads.json"))
            val seenCommitShas = SeenShas(dataDir.resolve("cache").resolve("seen-commit-shas.json"))
            val client = GithubClient(token, cache, etags, rateLimiter = RateLimiter(permits = Env.concurrency()))
            val sink = JsonlSink(dataDir.resolve("normalized"))
            return ScrapeContext(client, sink, cursor, etags, gitCursor, seenCommitShas, dataDir)
        }
    }
}
