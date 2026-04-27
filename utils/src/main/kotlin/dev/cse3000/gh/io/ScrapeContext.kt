package dev.cse3000.gh.io

import dev.cse3000.gh.cache.EtagStore
import dev.cse3000.gh.cache.RawCache
import dev.cse3000.gh.cache.SyncCursor
import dev.cse3000.gh.client.GithubClient
import java.nio.file.Path

class ScrapeContext(
    val client: GithubClient,
    val sink: JsonlSink,
    val cursor: SyncCursor,
    val etags: EtagStore,
    val dataDir: Path,
) : AutoCloseable {
    suspend fun persist() {
        etags.persist()
        cursor.persist()
    }

    override fun close() {
        runCatching { sink.close() }
        runCatching { client.close() }
    }

    companion object {
        fun create(): ScrapeContext {
            val token = Env.githubToken()
            val data = Env.dataDir()
            val cache = RawCache(data.resolve("cache").resolve("raw"))
            val etags = EtagStore(data.resolve("cache").resolve("etags.json"))
            val cursor = SyncCursor(data.resolve("cache").resolve("last-sync.json"))
            val client = GithubClient(token, cache, etags)
            val sink = JsonlSink(data.resolve("normalized"))
            return ScrapeContext(client, sink, cursor, etags, data)
        }
    }
}
