package dev.cse3000.gh.cache

import dev.cse3000.gh.io.Jsons
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SyncCursor(private val path: Path) {
    private val mutex = Mutex()
    private val map: MutableMap<String, String> = load(path)

    suspend fun get(key: String): String? = mutex.withLock { map[key] }

    suspend fun advance(key: String, isoTimestamp: String) = mutex.withLock {
        val cur = map[key]
        if (cur == null || cur < isoTimestamp) map[key] = isoTimestamp
    }

    suspend fun persist(): Unit = mutex.withLock {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Jsons.pretty.encodeToString(serializer, map))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private fun load(path: Path): MutableMap<String, String> {
            if (!Files.exists(path)) return mutableMapOf()
            val text = Files.readString(path)
            if (text.isBlank()) return mutableMapOf()
            return Jsons.compact.decodeFromString(serializer, text).toMutableMap()
        }
    }
}
