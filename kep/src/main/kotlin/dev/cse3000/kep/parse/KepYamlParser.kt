package dev.cse3000.kep.parse

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Loose YAML → JsonElement conversion. All scalars are emitted as JSON strings
 * so the downstream MySQL loader can coerce per-field. This avoids spurious
 * parse failures on quirky kep.yaml inputs (e.g. kep-number sometimes int,
 * sometimes string; trailing-comma free-form authors lists, etc.).
 */
object KepYamlParser {
    fun parseToJson(text: String): JsonElement = convert(Yaml.default.parseToYamlNode(text))

    private fun convert(node: YamlNode): JsonElement = when (node) {
        is YamlNull -> JsonNull
        is YamlScalar -> JsonPrimitive(node.content)
        is YamlList -> JsonArray(node.items.map(::convert))
        is YamlMap -> JsonObject(
            node.entries.entries.associate { (k, v) -> k.content to convert(v) }
        )
        is YamlTaggedNode -> convert(node.innerNode)
    }
}
