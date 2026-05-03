package dev.cse3000.gh.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class CachedResponse(val body: String, val link: String?)

class RawCache(private val root: Path) {
    private val mutex = Mutex()

    fun pathFor(url: String): Path {
        val hash = sha1(url)
        return root.resolve(hash.substring(0, 2)).resolve("$hash.json")
    }

    private fun linkPathFor(url: String): Path {
        val p = pathFor(url)
        return p.resolveSibling("${p.fileName}.link")
    }

    suspend fun get(url: String): CachedResponse? = mutex.withLock {
        val p = pathFor(url)
        if (!Files.exists(p)) return@withLock null
        val body = Files.readString(p)
        val linkPath = linkPathFor(url)
        val link = if (Files.exists(linkPath)) Files.readString(linkPath) else null
        CachedResponse(body, link)
    }

    suspend fun put(url: String, body: String, link: String? = null): Unit = mutex.withLock {
        val p = pathFor(url)
        Files.createDirectories(p.parent)
        Files.writeString(p, body)
        val linkPath = linkPathFor(url)
        if (link != null) Files.writeString(linkPath, link)
        else Files.deleteIfExists(linkPath)
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
