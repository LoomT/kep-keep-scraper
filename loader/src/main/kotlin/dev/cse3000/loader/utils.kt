package dev.cse3000.loader

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

internal inline fun <T, K> List<T>.distinctUntilChangedBy(selector: (T) -> K): List<T> =
    fold(mutableListOf()) { acc, item ->
        if (acc.isEmpty() || selector(acc.last()) != selector(item)) acc.add(item)
        acc
    }

internal fun JsonObject.getJsonString(key: String): String {
    val jsonPrimitive = this[key]!!.jsonPrimitive
    assert(jsonPrimitive.isString)
    return jsonPrimitive.content
}

/**
 * Reads [key] as a possibly-null JSON string. Returns `null` when the key is
 * missing, the value is `JsonNull`, or the value is not a string primitive.
 * Useful for fields like `body` which GitHub may serialize as `null` (e.g., an
 * issue / comment filed with an empty body).
 */
internal fun JsonObject.getJsonStringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val prim = element as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    return prim.content
}

/**
 * Reads `user.login` for a GitHub user-shaped object (`{ "login": ..., "id": ..., ... }`),
 * returning "ghost" when the user is `null` or the login is missing — matching GitHub's
 * own convention for deleted accounts (https://github.com/ghost).
 */
internal fun JsonObject.getLoginOrGhost(): String {
    val obj = this["user"] as? JsonObject ?: return "ghost"
    return obj.getJsonStringOrNull("login") ?: "ghost"
}