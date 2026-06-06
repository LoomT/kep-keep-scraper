package dev.cse3000.complexity.analyzer

import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class FileResult(
    val filePath: String,
    val language: String,
    val lines: Int,
    val codeLines: Int,
    val commentLines: Int,
    val blankLines: Int,
    val complexity: Int,
    val bytes: Long,
)

/**
 * Runs `scc --format json --by-file` on [analysisPath] and parses the JSON output.
 * Requires `scc` on PATH.
 */
fun runScc(analysisPath: Path, worktreeRoot: Path): List<FileResult> {
    val proc = ProcessBuilder("scc", "--format", "json", "--by-file", analysisPath.absolutePathString())
        .redirectErrorStream(false)
        .start()
    val jsonStr = proc.inputStream.bufferedReader().readText()
    val rc = proc.waitFor()
    if (rc != 0) error("scc failed (rc=$rc)")
    if (jsonStr.isBlank()) return emptyList()

    val rootPrefix = worktreeRoot.absolutePathString().replace('\\', '/').trimEnd('/') + "/"
    val json = Json { ignoreUnknownKeys = true }
    val languages = json.parseToJsonElement(jsonStr).jsonArray

    return buildList {
        for (langEntry in languages) {
            val obj = langEntry.jsonObject
            val language = obj["Name"]?.jsonPrimitive?.content ?: continue
            val files = obj["Files"]?.jsonArray ?: continue
            for (fileEntry in files) {
                val f = fileEntry.jsonObject
                val loc = (f["Location"]?.jsonPrimitive?.content ?: continue).replace('\\', '/')
                val relPath = if (loc.startsWith(rootPrefix)) loc.removePrefix(rootPrefix) else loc
                add(
                    FileResult(
                        filePath = relPath,
                        language = language,
                        lines = f["Lines"]?.jsonPrimitive?.int ?: 0,
                        codeLines = f["Code"]?.jsonPrimitive?.int ?: 0,
                        commentLines = f["Comment"]?.jsonPrimitive?.int ?: 0,
                        blankLines = f["Blank"]?.jsonPrimitive?.int ?: 0,
                        complexity = f["Complexity"]?.jsonPrimitive?.int ?: 0,
                        bytes = f["Bytes"]?.jsonPrimitive?.long ?: 0,
                    )
                )
            }
        }
    }
}
