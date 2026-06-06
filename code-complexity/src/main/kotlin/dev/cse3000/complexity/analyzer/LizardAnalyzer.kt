package dev.cse3000.complexity.analyzer

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.nio.file.Path
import kotlin.io.path.absolutePathString

data class FunctionResult(
    val filePath: String,
    val functionName: String,
    val startLine: Int,
    val endLine: Int,
    val nloc: Int,
    val cyclomaticComplexity: Int,
    val tokenCount: Int,
    val parameterCount: Int,
)

/**
 * Runs `lizard --csv` on [analysisPath] and parses the CSV output.
 * Requires `python` with `lizard` installed on PATH (install via `python -m pip install lizard`).
 */
fun runLizard(analysisPath: Path, worktreeRoot: Path): List<FunctionResult> {
    val proc = ProcessBuilder("python", "-m", "lizard", "--csv", analysisPath.absolutePathString())
        .redirectErrorStream(false)
        .start()
    val csv = proc.inputStream.bufferedReader().readText()
    val stderr = proc.errorStream.bufferedReader().readText()
    val rc = proc.waitFor()
    if (rc != 0 && csv.isBlank()) error("lizard failed (rc=$rc): $stderr")

    val rootPrefix = worktreeRoot.absolutePathString().replace('\\', '/').trimEnd('/') + "/"
    val format = CSVFormat.DEFAULT.builder().setHeader(
        "NLOC", "CCN", "tokens", "PARAM", "length",
        "location", "file", "function", "longName", "start", "end"
    ).setSkipHeaderRecord(false).get()
    return CSVParser.parse(csv, format).use { parser ->
        parser.records.mapNotNull { r ->
            val file = r["file"].replace('\\', '/')
            val relPath = if (file.startsWith(rootPrefix)) file.removePrefix(rootPrefix) else file
            FunctionResult(
                filePath = relPath,
                functionName = r.get("function"),
                startLine = r.get("start").toInt(),
                endLine = r.get("end").toInt(),
                nloc = r.get("NLOC").toInt(),
                cyclomaticComplexity = r.get("CCN").toInt(),
                tokenCount = r.get("tokens").toInt(),
                parameterCount = r.get("PARAM").toInt(),
            )
        }
    }
}
