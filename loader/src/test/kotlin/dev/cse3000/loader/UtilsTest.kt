package dev.cse3000.loader

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

class UtilsTest {

    @Test
    fun `getJsonString returns the content for a string-typed field`() {
        val obj = buildJsonObject { put("k", JsonPrimitive("hello")) }
        assertThat(obj.getJsonString("k")).isEqualTo("hello")
    }

    @Test
    fun `getJsonString throws for a missing field`() {
        val obj = buildJsonObject { put("other", JsonPrimitive("x")) }
        assertThatExceptionOfType(NullPointerException::class.java).isThrownBy { obj.getJsonString("k") }
    }

    @Test
    fun `getJsonStringOrNull returns null for missing keys`() {
        val obj = buildJsonObject { put("other", JsonPrimitive("x")) }
        assertThat(obj.getJsonStringOrNull("k")).isNull()
    }

    @Test
    fun `getJsonStringOrNull returns null for explicit JsonNull`() {
        val obj = buildJsonObject { put("k", JsonNull) }
        assertThat(obj.getJsonStringOrNull("k")).isNull()
    }

    @Test
    fun `getJsonStringOrNull returns null for non-string primitives`() {
        val obj = buildJsonObject { put("k", JsonPrimitive(42)) }
        assertThat(obj.getJsonStringOrNull("k")).isNull()
    }

    @Test
    fun `getJsonStringOrNull returns null for non-primitive values`() {
        val obj = buildJsonObject {
            put("k", buildJsonObject { put("nested", JsonPrimitive("v")) })
        }
        assertThat(obj.getJsonStringOrNull("k")).isNull()
    }

    @Test
    fun `getLoginOrGhost returns the login when present`() {
        val obj = buildJsonObject {
            put("user", buildJsonObject { put("login", JsonPrimitive("octocat")) })
        }
        assertThat(obj.getLoginOrGhost()).isEqualTo("octocat")
    }

    @Test
    fun `getLoginOrGhost returns ghost when user is null`() {
        val obj = buildJsonObject { put("user", JsonNull) }
        assertThat(obj.getLoginOrGhost()).isEqualTo("ghost")
    }

    @Test
    fun `getLoginOrGhost returns ghost when user has no login`() {
        val obj = buildJsonObject {
            put("user", buildJsonObject { put("id", JsonPrimitive(1)) })
        }
        assertThat(obj.getLoginOrGhost()).isEqualTo("ghost")
    }

    @Test
    fun `distinctUntilChangedBy collapses consecutive duplicates only`() {
        val input = listOf("a", "a", "b", "b", "a", "c", "c")
        val result = input.distinctUntilChangedBy { it }
        assertThat(result).containsExactly("a", "b", "a", "c")
    }

    @Test
    fun `distinctUntilChangedBy on an empty list returns empty`() {
        val result = emptyList<String>().distinctUntilChangedBy { it }
        assertThat(result).isEmpty()
    }

    @Test
    fun `distinctUntilChangedBy uses the selector key for change detection`() {
        data class Row(val id: String, val status: String)

        val rows = listOf(
            Row("a", "open"),
            Row("b", "open"),
            Row("c", "closed"),
            Row("d", "closed"),
            Row("e", "open"),
        )
        val statusChanges = rows.distinctUntilChangedBy { it.status }
        assertThat(statusChanges.map { it.id }).containsExactly("a", "c", "e")
    }
}
