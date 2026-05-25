package dev.cse3000.loader

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * Common pipeline for reading a rename-aware revision stream and grouping its
 * rows by a stable HEAD key.
 *
 * The walker (`RevisionWalker`) emits one row per (commit, historical-path) pair
 * and tags each row with `head_path` / `head_dir` — the path the file has at
 * HEAD, constant across the walk for one logical file even when its historical
 * name changes. Mappers group on those keys so a single logical proposal's
 * revisions stay together across renames.
 */
internal object RevisionGrouping {

    fun readGroupedByHeadPath(normalizedDir: Path, stream: String): Map<String, List<JsonObject>> =
        readGroupedBy(normalizedDir, stream, ::headPathOf)

    fun readGroupedByHeadDir(normalizedDir: Path, stream: String): Map<String, List<JsonObject>> =
        readGroupedBy(normalizedDir, stream, ::headDirOf)

    inline fun readGroupedBy(
        normalizedDir: Path,
        stream: String,
        crossinline key: (JsonObject) -> String,
    ): Map<String, List<JsonObject>> =
        readJsonlObjects(normalizedDir, stream)
            .keepLatestScrapesBy { it.getJsonString("path") to it.getJsonString("commit_sha") }
            .sortedBy { it.getJsonString("committed_at") }
            .groupBy { key(it) }

    fun headPathOf(obj: JsonObject): String = obj.getJsonString("head_path")

    fun headDirOf(obj: JsonObject): String = obj.getJsonString("head_dir")
}
