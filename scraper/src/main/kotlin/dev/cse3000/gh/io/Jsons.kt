package dev.cse3000.gh.io

import kotlinx.serialization.json.Json

object Jsons {
    val compact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
        explicitNulls = false
    }
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }
}
