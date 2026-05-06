package dev.cse3000.loader

import kotlinx.serialization.json.*
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
    private val personByName = mutableMapOf<String, Long>()

    private val proposalTextMetaKeysSeen = mutableSetOf<String>()

    fun mapAll(): Rows {
        val proposalGroups = mapProposals()
        val proposals = proposalGroups.map { it.proposal }

        val proposalIds = proposals.map { it.proposalId.removePrefix("KEEP-").toInt() }.toSet()

        val comments = mapComments(proposalIds)

        val rows = Rows(
            projects = listOf(
                Project(
                    projectId = projectId,
                    projectName = "Kotlin",
                    enhancementProposalName = "KEEP",
                    copyright = "Apache-2.0",
                ),
            ),
            proposals = proposals,
            proposalRevisions = proposalGroups.flatMap { it.revisions },
            proposalRevisionAuthors = proposalGroups.flatMap { it.authorRevisions },
            stageHistory = proposalGroups.flatMap { it.stages },
            comments = comments,
        )

        log.info("Meta keys seen: {}", proposalTextMetaKeysSeen)

        return rows
    }

    private fun mapProposals(): List<ProposalGroup> {
        val proposalGroupedCommits = readJsonlObjects(normalizedDir, "keep-proposal-revisions")
            .groupBy { it.getJsonString("path") }
            .map { (path, jsons) ->
                path to jsons.mapIndexed { index, json ->
                    ProposalCommit(
                        json.getJsonString("content_text").extractProposalTextMetaData(),
                        json.getJsonString("committed_at"),
                        index
                    )
                }
            }

        return proposalGroupedCommits.map { proposalCommits ->
            val proposalId = proposalCommits.first.removePrefix("proposals/").removePrefix("stdlib/").take(9)
            assert(proposalId.startsWith("KEEP-"))
            assert(proposalId.removePrefix("KEEP-").all(Char::isDigit))

            val topics = proposalCommits.second.mapNotNull { it.proposalData.topic }
            assert(topics.distinct().size > 1) { "Topic should not change in $proposalId, got ${topics.distinct()}" }

            val authorNames = proposalCommits.second.mapNotNull { it.proposalData.author }
            if (authorNames.isEmpty()) log.warn("Author is missing in $proposalId")

            val proposerId = authorNames.firstOrNull()?.let { resolveName(it) }

            val proposal = Proposal(
                projectId,
                proposalId,
                proposerId,
                topics.single(),
            )

            val proposalRevisions = proposalCommits.second.map { proposalCommit ->
                ProposalRevision(
                    projectId,
                    proposalId,
                    proposalCommit.index,
                    proposalCommit.proposalData.title,
                    proposalCommit.commitedAt,
                    proposalCommit.proposalData.trimmedContent,
                    proposalCommit.proposalData.implementedAt,
                )
            }

            val stages = proposalCommits.second
                .distinctUntilChangedBy { it.proposalData.status }
                .map { proposalCommit ->
                    StageHistory(
                        projectId,
                        proposalId,
                        proposalCommit.index,
                        proposalCommit.proposalData.status,
                        proposalCommit.commitedAt,
                    )
                }

            val proposalAuthorRevisions = proposalCommits.second
                .distinctUntilChangedBy { it.proposalData.author }
                .mapIndexed { index, proposalCommit ->
                    val authorId = proposalCommit.proposalData.author?.let { resolveName(it) }
                    ProposalRevisionAuthor(
                        projectId,
                        proposalId,
                        index,
                        authorId,
                    )
                }

            ProposalGroup(
                proposal,
                proposalRevisions,
                proposalAuthorRevisions,
                stages
            )
        }
    }

    private data class ProposalCommit(
        val proposalData: ProposalTextMetaData,
        val commitedAt: String,
        val index: Int
    )

    private data class ProposalTextMetaData(
        val trimmedContent: String,
        val title: String,
        val topic: String?, // called Type in KEEPs
        val author: String?,
        val contributors: String?,
        val status: String,
        val implementedAt: String?,
        val discussionLink: String?,
    )

    private data class ProposalGroup(
        val proposal: Proposal,
        val revisions: List<ProposalRevision>,
        val authorRevisions: List<ProposalRevisionAuthor>,
        val stages: List<StageHistory>
    )

    /** Memoizes login → person_id for this mapper's scope. */
    private fun resolveGHLogin(login: String): Long =
        personByGHLogin.getOrPut(login) { personIds.nextId() }

    private fun resolveEmail(email: String): Long =
        personByEmail.getOrPut(email) { personIds.nextId() }

    private fun resolveName(name: String): Long =
        personByName.getOrPut(name) { personIds.nextId() }

    private fun JsonObject.getJsonString(key: String): String {
        val jsonPrimitive = this[key]!!.jsonPrimitive
        assert(jsonPrimitive.isString)
        return jsonPrimitive.content
    }

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
                val valOrNull = value.ifBlank { null }
                if (key.contains("discussion")) ("discussion" to valOrNull) else (key to valOrNull)
            }

        val keys = metaPairs.map { it.first }
        val meta = '\n' + this.lines().takeWhile { it.startsWith("##") }.joinToString("\n")
        assert(keys.groupingBy { it }.eachCount().all { it.value == 1 }) {
            "duplicate keys in $meta"
        }
        proposalTextMetaKeysSeen.addAll(keys)

        val metaMap = metaPairs.toMap()

        if (metaMap["type"] == null) log.warn("Type field missing in {}", meta)

        val authors = listOfNotNull(metaMap["author"], metaMap["proposal author"])
        assert(authors.size <= 1)
        val author = authors.singleOrNull()
        if (author == null) log.warn("Author field missing in {}", meta)

        assert(metaMap["status"] != null) { "Status field missing in $meta" }
        val statusWithVersion = metaMap["status"]!!.split("in")
        val status = statusWithVersion[0].trim()
        if (status.lowercase() != "superseded")
            assert(!status.contains(' ')) { "Status was not split properly in $meta" }
        val implementedAt = statusWithVersion.getOrNull(1)?.trim()

        return ProposalTextMetaData(
            proposalTextWithoutMetaData,
            title,
            metaMap["type"],
            author,
            metaMap["contributors"] ?: metaMap["proposal contributors"],
            status,
            implementedAt,
            metaMap["discussion"]
        )
    }

    private fun mapComments(proposalIds: Set<Int>): List<Comment> {
        val issueComments = mapIssueComments(proposalIds)
        val discussionComments = mapDiscussions()
        return issueComments + discussionComments
    }

    private fun mapDiscussions(): List<Comment> {
        val discussionCommentJsons = readJsonlObjects(normalizedDir, "keep-discussion-comments")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.content }

        val discussionThreads = readJsonlObjects(normalizedDir, "keep-discussions")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.content }
            .filter { it["category"]!!.jsonObject.getJsonString("name") == "keep-discussions" }
            .map {
                val content = it.getJsonString("body")
                val proposalId = content.extractProposalIdFromDiscussionContent()
                DiscussionThread(
                    proposalId,
                    commentIds.nextId(),
                    it["number"]!!.jsonPrimitive.int,
                    it.getJsonString("title"),
                    it.getJsonString("body"),
                    it["author"]!!.jsonObject.getJsonString("login"),
                    it.getJsonString("createdAt"),
                )
            }

        val discussionComments = discussionThreads.flatMap { discussionThread ->
            discussionCommentJsons.filter { discussionComment ->
                discussionComment["_discussion"]!!.jsonPrimitive.int == discussionThread.discussionId
            }.flatMap { discussionCommentJson ->
                val proposalId = discussionThread.proposalId
                val authorId = resolveGHLogin(discussionCommentJson["author"]!!.jsonObject.getJsonString("login"))
                val discussionComment = Comment(
                    commentIds.nextId(),
                    authorId,
                    projectId,
                    proposalId,
                    discussionThread.commentId,
                    discussionCommentJson.getJsonString("createdAt"),
                    discussionCommentJson.getJsonString("body"),
                )

                discussionCommentJson["replies"]!!.jsonObject["nodes"]!!.jsonArray
                    .map { it.jsonObject }
                    .runningFold(discussionComment) { previous, replyJson ->
                        val authorId = resolveGHLogin(replyJson["author"]!!.jsonObject.getJsonString("login"))
                        Comment(
                            commentIds.nextId(),
                            authorId,
                            projectId,
                            proposalId,
                            previous.commentId,
                            replyJson.getJsonString("createdAt"),
                            replyJson.getJsonString("body"),
                        )
                    }
            }
        }

        return discussionThreads.map {
            Comment(
                it.commentId,
                resolveGHLogin(it.authorLogin),
                projectId,
                it.proposalId,
                null,
                it.createdAt,
                it.content,
            )
        }.toList().plus(discussionComments)
    }

    private data class DiscussionThread(
        val proposalId: String,
        val commentId: Long,
        val discussionId: Int,
        val title: String,
        val content: String,
        val authorLogin: String,
        val createdAt: String,
    )

    private fun String.extractProposalIdFromDiscussionContent(): String {
        //[here](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0440-bcv-to-kgp.md)
        assert(this.contains("https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-")) {
            "Discussion does not link to a KEEP"
        }
        return this.substringAfter("https://github.com/Kotlin/KEEP/blob/main/proposals/").take(9)
    }

    private fun mapIssueComments(proposalIds: Set<Int>): List<Comment> {
        val prIssueCommentIssueNumber = readJsonlObjects(normalizedDir, "keep-pr-issuecomments")
            .map { it.getJsonString("_issue").toInt() }
            .distinct()

        val issueToIssueComments = readJsonlObjects(normalizedDir, "keep-issue-comments")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.int }
            .groupBy { it.getJsonString("_issue").toInt() }

        return readJsonlObjects(normalizedDir, "keep-issues")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.int }
            .filter {
                it["number"]!!.jsonPrimitive.int in proposalIds
            }.flatMap { issue ->
                val issueNumber = issue["number"]!!.jsonPrimitive.int
                val proposalId = "KEEP-" + issueNumber.toString().padStart(4, '0')
                val rootContent = issue.getJsonString("body")
                val rootCreatedAt = issue.getJsonString("created_at")
                val rootLogin = issue["user"]!!.jsonObject.getJsonString("login")
                val rootAuthorId = resolveGHLogin(rootLogin)

                if (issueNumber in prIssueCommentIssueNumber)
                    log.error("proposal id $issueNumber exists in keep-pr-issuecomments")

                val rootComment = Comment(
                    commentIds.nextId(),
                    rootAuthorId,
                    projectId,
                    proposalId,
                    null,
                    rootCreatedAt,
                    rootContent,
                )

                val issueComments = issueToIssueComments[issueNumber] ?: emptyList()

                val commentIdBuffer =
                    listOf(rootComment.commentId) + (0..issueComments.size).map { commentIds.nextId() }

                issueComments.sortedBy { it.getJsonString("created_at") }
                    .runningFold(rootComment) { previous, commentJson ->
                        val login = commentJson["user"]!!.jsonObject.getJsonString("login")
                        Comment(
                            commentId = commentIds.nextId(),
                            authorId = resolveGHLogin(login),
                            projectId = projectId,
                            proposalId = proposalId,
                            commentOnCommentId = previous.commentId,
                            createdAt = commentJson.getJsonString("created_at"),
                            content = commentJson.getJsonString("body"),
                        )
                    }

            }.toList()
    }
}
