package dev.cse3000.gh.cache

import dev.cse3000.gh.io.Jsons
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Append-only set of commit SHAs already fetched by `CommitsCollector`, keyed by
 * repo slug. Drives the incremental fetch: any reachable commit in the local git
 * mirror whose SHA isn't in this set is a new commit to fetch from GitHub.
 *
 * Append-only by design: orphaned SHAs (force-pushed away) remain in the set so
 * we never refetch them. The historical JSONL row for an orphan stays too — the
 * login → (name, email) data it contributed is still valid.
 */
class SeenShas(private val path: Path) {
    private val mutex = Mutex()
    private val map: MutableMap<String, MutableSet<String>> = load(path)

    suspend fun get(slug: String): Set<String> = mutex.withLock {
        map[slug]?.toSet() ?: emptySet()
    }

    suspend fun add(slug: String, sha: String): Unit = mutex.withLock {
        map.getOrPut(slug) { mutableSetOf() } += sha
    }

    suspend fun addAll(slug: String, shas: Collection<String>): Unit = mutex.withLock {
        if (shas.isEmpty()) return@withLock
        map.getOrPut(slug) { mutableSetOf() } += shas
    }

    suspend fun persist(): Unit = mutex.withLock {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Jsons.pretty.encodeToString(serializer, map))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val serializer = MapSerializer(
            String.serializer(),
            SetSerializer(String.serializer()),
        )

        private fun load(path: Path): MutableMap<String, MutableSet<String>> {
            if (!Files.exists(path)) return mutableMapOf()
            val text = Files.readString(path)
            if (text.isBlank()) return mutableMapOf()
            return Jsons.compact.decodeFromString(serializer, text)
                .mapValues { (_, v) -> v.toMutableSet() }
                .toMutableMap()
        }
    }
}
