package dev.cse3000.loader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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