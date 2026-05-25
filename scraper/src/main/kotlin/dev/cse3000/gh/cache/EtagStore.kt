package dev.cse3000.gh.cache

import dev.cse3000.gh.io.Jsons
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path

class EtagStore(private val path: Path) {
    private val mutex = Mutex()
    private val map: MutableMap<String, String> = load(path)

    suspend fun get(url: String): String? = mutex.withLock { map[url] }

    suspend fun put(url: String, etag: String) = mutex.withLock { map[url] = etag }

    suspend fun persist() = mutex.withLock { writeAtomic(path, map) }

    companion object {
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private fun load(path: Path): MutableMap<String, String> {
            if (!Files.exists(path)) return mutableMapOf()
            val text = Files.readString(path)
            if (text.isBlank()) return mutableMapOf()
            return Jsons.compact.decodeFromString(serializer, text).toMutableMap()
        }

        private fun writeAtomic(path: Path, map: Map<String, String>) {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, Jsons.pretty.encodeToString(serializer, map))
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
