package dev.cse3000.gh.io

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class JsonlWriter(path: Path) : AutoCloseable {
    private val mutex = Mutex()
    private val writer: BufferedWriter

    init {
        Files.createDirectories(path.parent)
        writer = Files.newBufferedWriter(
            path,
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }

    suspend fun append(element: JsonElement) {
        val line = Jsons.compact.encodeToString(JsonElement.serializer(), element)
        mutex.withLock {
            writer.write(line)
            writer.newLine()
        }
    }

    suspend fun flush() = mutex.withLock { writer.flush() }

    override fun close() {
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }
}
