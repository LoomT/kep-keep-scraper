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

/**
 * Keep only the latest scrapes for each key (selector).
 */
internal inline fun <T> Sequence<JsonObject>.keepLatestScrapesBy(crossinline selector: (JsonObject) -> T): Sequence<JsonObject> =
    groupingBy { selector(it) }
        .reduce { _, acc, obj ->
            if (obj["_scraped_at"]!!.jsonPrimitive.content > acc["_scraped_at"]!!.jsonPrimitive.content) obj else acc
        }
        .values
        .asSequence()

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