package dev.cse3000.kep

import dev.cse3000.gh.io.ScrapeContext
import dev.cse3000.kep.parse.KepYamlParser
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.*

class KepDiscovery(private val ctx: ScrapeContext) {
    private val log = LoggerFactory.getLogger(KepDiscovery::class.java)

    companion object {
        const val OWNER = "kubernetes"
        const val REPO = "enhancements"
        private const val SLUG = "$OWNER/$REPO"
    }

    suspend fun run(limit: Int? = null) {
        log.info("KEP discovery starting (limit={})", limit)
        val repoInfo = ctx.client.getJson(ctx.client.apiUrl("/repos/$SLUG")).jsonObject
        ctx.sink.emit("kep-repo-info", repoInfo, mapOf("repo" to SLUG))
        val defaultBranch = repoInfo["default_branch"]?.jsonPrimitive?.contentOrNull ?: "master"

        val branchInfo = ctx.client.getJson(ctx.client.apiUrl("/repos/$SLUG/branches/$defaultBranch")).jsonObject
        val headSha = branchInfo["commit"]!!.jsonObject["sha"]!!.jsonPrimitive.content

        val tree = ctx.client.getJson(
            ctx.client.apiUrl("/repos/$SLUG/git/trees/$headSha", mapOf("recursive" to "1"))
        ).jsonObject
        ctx.sink.emit("kep-tree", tree, mapOf("repo" to SLUG, "sha" to headSha, "branch" to defaultBranch))

        val items = tree["tree"]?.jsonArray.orEmpty()
        val kepYamlEntries = items.filter { entry ->
            val path = entry.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: return@filter false
            path.startsWith("keps/") && path.endsWith("/kep.yaml")
        }
        val capped = if (limit != null) kepYamlEntries.take(limit) else kepYamlEntries
        log.info(
            "Discovered {} kep.yaml files at {}; processing {}",
            kepYamlEntries.size, headSha, capped.size
        )

        var processed = 0
        for (entry in capped) {
            val obj = entry.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val blobSha = obj["sha"]?.jsonPrimitive?.contentOrNull
            try {
                processKep(path, blobSha)
            } catch (e: Throwable) {
                log.warn("Failed to process {}: {}", path, e.message)
            }
            processed++
            if (processed % 25 == 0) {
                log.info(
                    "KEP discovery progress: {}/{} processed (rate-limit remaining: {})",
                    processed, capped.size, ctx.client.rateLimiter.remainingSnapshot
                )
            }
        }
        log.info("KEP discovery done: {} kep.yaml files processed", processed)
    }

    private suspend fun processKep(path: String, blobSha: String?) {
        val resp = ctx.client.getJson(ctx.client.apiUrl("/repos/$SLUG/contents/$path")).jsonObject
        val contentB64 = resp["content"]?.jsonPrimitive?.contentOrNull
            ?.replace("\n", "")
            ?.replace("\r", "")
            ?: return
        val yamlText = Base64.getDecoder().decode(contentB64).toString(Charsets.UTF_8)

        ctx.sink.emit(
            "kep-yaml-raw",
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("blob_sha", JsonPrimitive(blobSha))
                put("yaml_text", JsonPrimitive(yamlText))
            },
            mapOf("repo" to SLUG),
        )

        val parsed = runCatching { KepYamlParser.parseToJson(yamlText) }
            .onFailure { log.warn("YAML parse failed at {}: {}", path, it.message) }
            .getOrNull()
        if (parsed is JsonObject) {
            val enriched = JsonObject(
                parsed + mapOf(
                    "_path" to JsonPrimitive(path),
                    "_blob_sha" to JsonPrimitive(blobSha),
                )
            )
            ctx.sink.emit("kep-yaml", enriched, mapOf("repo" to SLUG))
        } else {
            log.warn("Non-object kep.yaml at {}; only raw text emitted", path)
        }
    }
}
