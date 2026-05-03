package dev.cse3000.gh.io

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class JsonlSink(private val root: Path) : AutoCloseable {
    private val writers = ConcurrentHashMap<String, JsonlWriter>()
    val counts = ConcurrentHashMap<String, Int>()

    fun writer(stream: String): JsonlWriter =
        writers.computeIfAbsent(stream) { JsonlWriter(root.resolve("$it.jsonl")) }

    suspend fun emit(stream: String, element: JsonElement, meta: Map<String, String> = emptyMap()) {
        val withMeta = if (element is JsonObject) {
            val extras = buildMap {
                put("_scraped_at", JsonPrimitive(Instant.now().toString()))
                meta.forEach { (k, v) -> put("_$k", JsonPrimitive(v)) }
            }
            JsonObject(element + extras)
        } else element
        writer(stream).append(withMeta)
        counts.merge(stream, 1) { a, b -> a + b }
    }

    suspend fun flushAll() {
        writers.values.forEach { runCatching { it.flush() } }
    }

    override fun close() {
        writers.values.forEach { runCatching { it.close() } }
    }
}
