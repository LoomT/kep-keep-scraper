package dev.cse3000.loader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads a JSONL file (`<dir>/<stream>.jsonl`) and returns each line as a
 * [JsonObject]. Lines that aren't objects (or fail to parse) are skipped.
 * Returns an empty sequence if the file does not exist.
 */
fun readJsonlObjects(dir: Path, stream: String): Sequence<JsonObject> {
    val path = dir.resolve("$stream.jsonl")
    if (!Files.exists(path)) return emptySequence()
    return sequence {
        Files.newBufferedReader(path).use { reader ->
            for (line in reader.lines()) {
                if (line.isBlank()) continue
                val element: JsonElement = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: continue
                if (element is JsonObject) yield(element)
            }
        }
    }
}
