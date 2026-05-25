package dev.cse3000.gh.io

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class RunManifest(
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    val mode: String,
    val repo: String,
    @SerialName("rate_limit_remaining_at_end") val rateLimitRemainingAtEnd: Int? = null,
    @SerialName("requests_made") val requestsMade: Int,
    @SerialName("requests_304") val requests304: Int,
    val counts: Map<String, Int>,
    val cancelled: Boolean = false,
    val errors: List<String> = emptyList(),
)

object RunManifestWriter {
    fun write(dir: Path, manifest: RunManifest): Path {
        Files.createDirectories(dir)
        val safe = manifest.startedAt.replace(":", "-").replace(".", "-")
        val filename = "${manifest.repo.replace('/', '_')}-${manifest.mode}-$safe.json"
        val path = dir.resolve(filename)
        Files.writeString(path, Jsons.pretty.encodeToString(RunManifest.serializer(), manifest))
        return path
    }
}
