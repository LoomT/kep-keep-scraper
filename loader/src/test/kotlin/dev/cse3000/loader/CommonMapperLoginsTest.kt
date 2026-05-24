package dev.cse3000.loader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommonMapperLoginsTest {

    @Test
    fun `aggregates git names and emails per login across streams`() {
        val streamA = sequenceOf(
            commit(login = "alice", name = "Alice One", email = "alice@A.com"),
            commit(login = "alice", name = "Alice One", email = "alice@A.com"), // duplicate — set dedups
            commit(login = "alice", name = "Alice Renamed", email = "alice@B.com"),
            commit(login = "bob", name = "Bob", email = "bob@x.com"),
        )
        val streamB = sequenceOf(
            commit(login = "alice", name = "Alice One", email = "ALICE@a.com"), // emails lowercased
        )

        val result = CommonMapper.loginsToGitAuthors(listOf(streamA, streamB))

        assertThat(result.gitNamesByLogin)
            .containsEntry("alice", setOf("Alice One", "Alice Renamed"))
            .containsEntry("bob", setOf("Bob"))
        assertThat(result.gitEmailsByLogin)
            .describedAs("emails are lowercased before being added to the set")
            .containsEntry("alice", setOf("alice@a.com", "alice@b.com"))
            .containsEntry("bob", setOf("bob@x.com"))
    }

    @Test
    fun `skips commits whose github author is null`() {
        // Real GitHub responses have `"author": null` when GitHub couldn't match the
        // commit email to any user — we should ignore those rather than blow up.
        val stream = sequenceOf(
            commit(login = "alice", name = "Alice", email = "alice@x.com"),
            commitNoLogin(name = "Anon", email = "anon@x.com"),
            commitMissingAuthor(),
        )
        val result = CommonMapper.loginsToGitAuthors(listOf(stream))
        assertThat(result.gitNamesByLogin).containsOnlyKeys("alice")
        assertThat(result.gitEmailsByLogin).containsOnlyKeys("alice")
    }

    @Test
    fun `skips commits with a missing commit-author block`() {
        val stream = sequenceOf(
            commit(login = "alice", name = "Alice", email = "alice@x.com"),
            buildJsonObject {
                put("sha", JsonPrimitive("nocommit"))
                put("author", buildJsonObject { put("login", JsonPrimitive("ghost")) })
                // No `commit` field at all — must be skipped.
            },
        )
        val result = CommonMapper.loginsToGitAuthors(listOf(stream))
        assertThat(result.gitNamesByLogin).containsOnlyKeys("alice")
    }

    @Test
    fun `treats blank login as missing`() {
        val stream = sequenceOf(
            commit(login = "alice", name = "Alice", email = "alice@x.com"),
            commit(login = "   ", name = "blank", email = "blank@x.com"),
            commit(login = "", name = "empty", email = "empty@x.com"),
        )
        val result = CommonMapper.loginsToGitAuthors(listOf(stream))
        assertThat(result.gitNamesByLogin).containsOnlyKeys("alice")
    }

    @Test
    fun `tolerates missing name or email`() {
        val stream = sequenceOf(
            commit(login = "alice", name = "Alice", email = null),
            commit(login = "alice", name = null, email = "alice@x.com"),
            commit(login = "alice", name = "", email = ""),
        )
        val result = CommonMapper.loginsToGitAuthors(listOf(stream))
        assertThat(result.gitNamesByLogin["alice"]).containsExactly("Alice")
        assertThat(result.gitEmailsByLogin["alice"]).containsExactly("alice@x.com")
    }

    @Test
    fun `empty streams produce empty result`() {
        val result = CommonMapper.loginsToGitAuthors(listOf(emptySequence(), emptySequence()))
        assertThat(result.gitNamesByLogin).isEmpty()
        assertThat(result.gitEmailsByLogin).isEmpty()
    }

    private fun commit(login: String, name: String?, email: String?): JsonObject = buildJsonObject {
        put("sha", JsonPrimitive("sha-$login-$name-$email"))
        put("author", buildJsonObject { put("login", JsonPrimitive(login)) })
        put(
            "commit",
            buildJsonObject {
                put(
                    "author",
                    buildJsonObject {
                        if (name != null) put("name", JsonPrimitive(name))
                        if (email != null) put("email", JsonPrimitive(email))
                    },
                )
            },
        )
    }

    private fun commitNoLogin(name: String, email: String): JsonObject = buildJsonObject {
        put("sha", JsonPrimitive("sha-no-login"))
        // No `author` key — simulates GitHub's "author: null" case after JSON parsing.
        put(
            "commit",
            buildJsonObject {
                put(
                    "author",
                    buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("email", JsonPrimitive(email))
                    },
                )
            },
        )
    }

    private fun commitMissingAuthor(): JsonObject = buildJsonObject {
        put("sha", JsonPrimitive("sha-missing"))
        put("commit", buildJsonObject { /* no author block */ })
    }
}
