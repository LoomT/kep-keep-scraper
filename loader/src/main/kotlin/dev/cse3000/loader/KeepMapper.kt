package dev.cse3000.loader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(KeepMapper::class.java)

/**
 * Maps `keep-*.jsonl` streams (under [normalizedDir]) to typed [Rows] for the
 * KEEP project.
 */
class KeepMapper(
    private val projectId: Int,
    private val normalizedDir: Path,
    private val personIds: IdAllocator,
    private val organisationIds: IdAllocator,
    private val commentIds: IdAllocator,
) {
    /** Maps each GitHub `login` (assumed unique within a single contributor) to a stable person_id. */
    private val personByGHLogin = mutableMapOf<String, Long>()
    private val personByEmail = mutableMapOf<String, Long>()

    private val proposalTextMetaKeysSeen = mutableSetOf<String>()

    fun mapAll(): Rows {
        val rows = Rows(
            projects = listOf(
                Project(
                    projectId = projectId,
                    projectName = "Kotlin",
                    enhancementProposalName = "KEEP",
                    copyright = "Apache-2.0",
                ),
            ),
        )

        // TODO: read JSONL streams from `normalizedDir`, build SchemaModel rows, fold into `rows`.
        // Helpers available:
        //   readJsonlObjects(normalizedDir, "keep-pulls").forEach { obj -> ... }
        //   personIds.nextId()                    // allocate a fresh person_id
        //   resolveLogin(login, fullName) -> Long // memoized within this mapper
        //
        // Example skeleton:
        //   val proposals = mutableListOf<Proposal>()
        //   val revisions = mutableListOf<ProposalRevision>()
        //   readJsonlObjects(normalizedDir, "keep-pulls").forEach { pr ->
        //       val number = pr["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
        //       proposals += Proposal(projectId, "KEEP-$number", proposerId = ..., topic = ..., proposalType = ...)
        //   }
        //   return rows.copy(proposals = proposals, ...)

        log.info("Meta keys seen: {}", proposalTextMetaKeysSeen)

        return rows
    }

    private fun mapProposals(): List<ProposalGroup> {
        val proposalGroupedCommits = readJsonlObjects(normalizedDir, "keep-proposal-revisions")
            .groupBy { it.getJsonString("path") }
            .map { (path, jsons) ->
                path to jsons.mapIndexed { index, json ->
                    ProposalCommit(
                        json.getJsonString("content_text"),
                        json.getJsonString("committed_at"),
                        index
                    )
                }
            }
        return proposalGroupedCommits.map { proposalCommits ->
            val proposalMetas = proposalCommits.second.map { proposalCommit ->
                proposalCommit.contentText.extractProposalTextMetaData()
            }
            val proposalId = proposalCommits.first.removePrefix("proposals/").removePrefix("stdlib/").take(9)
            assert(proposalId.startsWith("KEEP-"))
            assert(proposalId.removePrefix("KEEP-").all(Char::isDigit))
            val topics = proposalMetas.map { it.topic }
            assert(topics.distinct().size > 1) { "Topic should not change in $proposalId, got ${topics.distinct()}" }
//            val proposal = Proposal(
//                projectId,
//                proposalId,
//                proposerId =
//                topic = topics.single(),
//                proposalType = null
//            )
            return emptyList()
        }
    }

    private data class ProposalCommit(
        val contentText: String,
        val commitedAt: String,
        val index: Int
    )

    private data class ProposalGroup(
        val proposal: Proposal,
        val revisions: List<ProposalRevision>,
        val authorRevisions: List<ProposalRevisionAuthor>,
        val stages: List<StageHistory>
    )

    /** Memoizes login → person_id for this mapper's scope. */
    private fun resolveGHLogin(login: String, fullName: String? = null): Long =
        personByGHLogin.getOrPut(login) { personIds.nextId() }

    private fun resolveEmail(email: String, fullName: String? = null): Long =
        personByEmail.getOrPut(email) { personIds.nextId() }

    private fun JsonObject.getJsonString(key: String): String {
        val jsonPrimitive = this[key]!!.jsonPrimitive
        assert(jsonPrimitive.isString)
        return jsonPrimitive.content
    }

    private data class ProposalTextMetaData(
        val trimmedContent: String,
        val title: String,
        val topic: String, // called Type in KEEPs
        val author: String,
        val contributors: String?,
        val status: String,
        val implementedAt: String?,
        val discussionLink: String?,
    )

    private fun String.extractProposalTextMetaData(): ProposalTextMetaData {
        val lines = this.lines()
        val title = lines[0].removePrefix("# ").trim()
        assert(title.isNotBlank()) { "Title was not found" }

        val proposalTextWithoutMetaData = lines.drop(1).dropWhile { it.startsWith("##") }.joinToString("\n")

        val metaPairs = lines.drop(1)
            .takeWhile { it.startsWith("##") }
            .map { it.removePrefix("* ") }
            .map { metaLine ->
                val key = metaLine.removePrefix("**").takeWhile { it == '*' }.lowercase()
                val value = metaLine.dropWhile { it == ':' }.trim()
                if (key.contains("discussion")) ("discussion" to value) else (key to value)
            }

        val keys = metaPairs.map { it.first }
        val meta = '\n' + this.lines().takeWhile { it.startsWith("##") }.joinToString("\n")
        assert(keys.groupingBy { it }.eachCount().all { it.value == 1 }) {
            "duplicate keys in $meta"
        }
        proposalTextMetaKeysSeen.addAll(keys)

        val metaMap = metaPairs.toMap()

        assert(metaMap["type"] != null) { "Type field missing in $meta" }
        val topic = metaMap["type"]!!
        assert(metaMap["author"] != null) { "Author field missing in $meta" }
        val author = metaMap["author"]!!
        val contributors = metaMap["contributors"]
        assert(metaMap["status"] != null) { "Status field missing in $meta" }
        val statusWithVersion = metaMap["status"]!!.split("in")
        val status = statusWithVersion[0].trim()
        assert(!status.contains(' ')) { "Status was not split properly in $meta" }
        val implementedAt = statusWithVersion.getOrNull(1)?.trim()
        val discussion = metaMap["discussion"]

        return ProposalTextMetaData(
            proposalTextWithoutMetaData,
            title,
            topic,
            author,
            contributors,
            status,
            implementedAt,
            discussion
        )
    }
}
