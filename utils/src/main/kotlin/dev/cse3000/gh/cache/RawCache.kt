package dev.cse3000.gh.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RawCache(private val root: Path) {
    private val mutex = Mutex()

    fun pathFor(url: String): Path {
        val hash = sha1(url)
        return root.resolve(hash.substring(0, 2)).resolve("$hash.json")
    }

    suspend fun get(url: String): String? = mutex.withLock {
        val p = pathFor(url)
        if (Files.exists(p)) Files.readString(p) else null
    }

    suspend fun put(url: String, body: String) = mutex.withLock {
        val p = pathFor(url)
        Files.createDirectories(p.parent)
        Files.writeString(p, body)
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
