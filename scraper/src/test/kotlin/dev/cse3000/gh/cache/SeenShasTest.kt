package dev.cse3000.gh.cache

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SeenShasTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `get on an empty store returns an empty set`() = runTest {
        val store = SeenShas(tempDir.resolve("seen.json"))
        assertThat(store.get("any/slug")).isEmpty()
    }

    @Test
    fun `add and get are namespaced by slug`() = runTest {
        val store = SeenShas(tempDir.resolve("seen.json"))
        store.add("a/r", "sha1")
        store.add("a/r", "sha2")
        store.add("b/r", "shaX")
        assertThat(store.get("a/r")).containsExactlyInAnyOrder("sha1", "sha2")
        assertThat(store.get("b/r")).containsExactly("shaX")
        assertThat(store.get("c/r")).isEmpty()
    }

    @Test
    fun `addAll merges into the existing set without dropping pre-existing SHAs`() = runTest {
        val store = SeenShas(tempDir.resolve("seen.json"))
        store.add("a/r", "sha1")
        store.addAll("a/r", listOf("sha2", "sha3"))
        store.addAll("a/r", emptyList()) // no-op
        assertThat(store.get("a/r")).containsExactlyInAnyOrder("sha1", "sha2", "sha3")
    }

    @Test
    fun `persist and reload preserves namespaced sets`() = runTest {
        val path = tempDir.resolve("seen.json")
        SeenShas(path).also {
            it.add("a/r", "sha1")
            it.add("a/r", "sha2")
            it.add("b/r", "shaX")
            it.persist()
        }
        // Cross-process equivalent: brand-new instance reads the on-disk state.
        val reloaded = SeenShas(path)
        assertThat(reloaded.get("a/r")).containsExactlyInAnyOrder("sha1", "sha2")
        assertThat(reloaded.get("b/r")).containsExactly("shaX")
    }

    @Test
    fun `missing file loads as empty`() = runTest {
        val store = SeenShas(tempDir.resolve("does-not-exist.json"))
        assertThat(store.get("any")).isEmpty()
    }

    @Test
    fun `blank file loads as empty`() = runTest {
        val path = tempDir.resolve("blank.json")
        Files.writeString(path, "   \n  ")
        val store = SeenShas(path)
        assertThat(store.get("any")).isEmpty()
    }

    @Test
    fun `persist is atomic - tmp file is cleaned up after move`() = runTest {
        val path = tempDir.resolve("nested").resolve("seen.json")
        val store = SeenShas(path)
        store.add("a/r", "sha1")
        store.persist()
        assertThat(Files.exists(path)).isTrue()
        assertThat(Files.list(path.parent).use { it.toList() })
            .describedAs("only the final file should remain; the .tmp staging file must be moved")
            .singleElement()
            .extracting { (it as Path).fileName.toString() }
            .isEqualTo("seen.json")
    }

    @Test
    fun `adding the same sha twice is idempotent`() = runTest {
        val store = SeenShas(tempDir.resolve("seen.json"))
        store.add("a/r", "sha1")
        store.add("a/r", "sha1")
        store.add("a/r", "sha1")
        assertThat(store.get("a/r")).containsExactly("sha1")
    }
}
