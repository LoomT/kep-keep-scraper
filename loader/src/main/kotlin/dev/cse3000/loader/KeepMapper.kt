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

        val persons = mutableListOf<Person>()
        val personUsernames = mutableListOf<PersonUsername>()
        for ((login, id) in personByGHLogin) {
            persons += Person(personId = id, fullName = null)
            personUsernames += PersonUsername(personId = id, domain = "github.com", username = login, realName = null)
        }
        for ((email, id) in personByEmail) {
            persons += Person(personId = id, fullName = null)
            personUsernames += PersonUsername(personId = id, domain = "email", username = email, realName = null)
        }
        for ((name, id) in personByName) {
            persons += Person(personId = id, fullName = name)
        }

        val rows = Rows(
            projects = listOf(
                Project(
                    projectId = projectId,
                    projectName = "Kotlin",
                    enhancementProposalName = "KEEP",
                    copyright = "Apache-2.0",
                ),
            ),
            persons = persons,
            personUsernames = personUsernames,
            proposals = proposals,
            proposalRevisions = proposalGroups.flatMap { it.revisions },
            proposalRevisionAuthors = proposalGroups.flatMap { it.authorRevisions },
            stageHistory = proposalGroups.flatMap { it.stages },
            comments = comments,
        )

        log.info("Meta keys seen: {}", proposalTextMetaKeysSeen)
        log.info(
            "Resolver counts: GH logins={}, emails={}, names={}, total Person rows={}",
            personByGHLogin.size, personByEmail.size, personByName.size, persons.size,
        )
        log.info("Proposal distinct statuses: {}", rows.stageHistory.map { it.status }.distinct())

        return rows
    }

    private fun mapProposals(): List<ProposalGroup> {
        val proposalGroupedCommits = readJsonlObjects(normalizedDir, "keep-proposal-revisions")
            .groupBy { it.getJsonString("path") }
            .filterNot { it.key.contains("TEMPLATE.md") }
            .map { (path, jsons) ->
                path to jsons.mapIndexed { index, json ->
                    val parsed = try {
                        json.getJsonString("content_text").extractProposalTextMetaData()
                    } catch (e: Throwable) {
                        throw AssertionError(
                            "Failed to parse proposal meta in $path (commit index $index, sha=${
                                runCatching { json.getJsonString("commit_sha").take(8) }.getOrDefault("?")
                            }): ${e.message}",
                            e,
                        )
                    }
                    ProposalCommit(
                        parsed,
                        json.getJsonString("committed_at"),
                        index
                    )
                }
            }

        return proposalGroupedCommits.mapNotNull { proposalCommits ->
            val proposalId = proposalCommits.first.removePrefix("proposals/").removePrefix("stdlib/").take(9)
            assert(proposalId.startsWith("KEEP-")) {
                "Proposal id should start with KEEP-, got '$proposalId' for path '${proposalCommits.first}'"
            }
            assert(proposalId.removePrefix("KEEP-").all(Char::isDigit)) {
                "Proposal id should be KEEP-<digits>, got '$proposalId' for path '${proposalCommits.first}'"
            }

            val topics = proposalCommits.second.mapNotNull { it.proposalData.topic }
                .ifEmpty {
                    // decide on general topic based on if it's an stdlib proposal or a regular one
                    if (proposalCommits.first.contains("proposals/stdlib/KEEP-"))
                        listOf("Standard Library API proposal")
                    else
                        listOf("Design proposal")
                }
            assert(topics.distinct().size == 1) {
                "Topic should not change across revisions in $proposalId, got ${topics.distinct()} (count=${topics.size})"
            }
            val topic = topics.distinct().single()

            val authorNames = proposalCommits.second.mapNotNull { it.proposalData.author }
            if (authorNames.isEmpty()) log.warn("Author is missing in $proposalId")

            val proposerId = authorNames.firstOrNull()?.let { resolveName(it) }

            val proposal = Proposal(
                projectId,
                proposalId,
                proposerId,
                topic,
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

    /**
     * Reads [key] as a possibly-null JSON string. Returns `null` when the key is
     * missing, the value is `JsonNull`, or the value is not a string primitive.
     * Useful for fields like `body` which GitHub may serialize as `null` (e.g., an
     * issue / comment filed with an empty body).
     */
    private fun JsonObject.getJsonStringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val prim = element as? JsonPrimitive ?: return null
        if (!prim.isString) return null
        return prim.content
    }

    private fun String.extractProposalTextMetaData(): ProposalTextMetaData {
        val nonBlankLines = this.lines().filter { it.isNotBlank() }
        val title = nonBlankLines[0].removePrefix("# ").trim()
        assert(title.isNotBlank()) { "Title was not found" }

        val proposalTextWithoutMetaData = this.lines()
            .dropWhile { it.isBlank() }
            .drop(1)
            .dropWhile { !it.isLineAfterMetadata() }
            .joinToString("\n")

        val rawMetaLines = nonBlankLines.drop(1).takeWhile { !it.isLineAfterMetadata() }
        val metaLines = mutableListOf<String>()
        for (line in rawMetaLines) {
            if (line.startsWith("* ") || line.startsWith("- ")) {
                metaLines += line
            } else if (metaLines.isNotEmpty()) {
                // Continuation: append, joined with a single space, leading indent stripped.
                metaLines[metaLines.lastIndex] = metaLines.last().trimEnd() + " " + line.trim()
            } else {
                log.warn("Meta line before any bullet entry; ignoring: '{}'", line)
            }
        }

        val metaPairs = metaLines
            .map { it.removePrefix("* ").removePrefix("- ") }
            .map { metaLine ->
                // Handle both `**Key**: value` and `**Key:** value` (colon inside the bold).
                val key = metaLine.removePrefix("**")
                    .substringBefore(":", missingDelimiterValue = "")
                    .removeSuffix("**")
                    .lowercase()
                    .trim()
                val value = metaLine.substringAfter(":", missingDelimiterValue = "").trim()
                val valOrNull = value.ifBlank { null }
                if (key.contains("discussion")) ("discussion" to valOrNull) else (key to valOrNull)
            }.toList()

        val keys = metaPairs.map { it.first }
        val metaWithTitle = "\nTITLE: $title\n" + metaLines.joinToString("\n")
        assert(keys.groupingBy { it }.eachCount().all { it.value == 1 }) {
            "duplicate keys $keys in $metaWithTitle"
        }
        proposalTextMetaKeysSeen.addAll(keys)

        val metaMap = metaPairs.toMap()

        if (metaMap["type"] == null) log.warn("Type field missing in {}", metaWithTitle)

        val authors = listOfNotNull(metaMap["author"], metaMap["proposal author"])
        assert(authors.size <= 1)
        val author = authors.singleOrNull()
        if (author == null) log.warn("Author field missing in {}", metaWithTitle)

        if (metaMap["status"] == null) log.warn("Status field missing in {}", metaWithTitle)

        val rawStatus = metaMap["status"] ?: "Unknown"
        val (status, implementedAt) = when {
            rawStatus.contains("superseded", ignoreCase = true) -> rawStatus to null

            // Order matters: more specific separators first so we extract the right "post" string
            // before the generic " in " fallback can match.
            rawStatus.contains(" since Kotlin ", ignoreCase = true) -> {
                val (s, rest) = rawStatus.split(" since Kotlin ", ignoreCase = true, limit = 2)
                s.trim() to extractKotlinVersion(rest)
            }

            rawStatus.contains(" in Kotlin ", ignoreCase = true) -> {
                val (s, rest) = rawStatus.split(" in Kotlin ", ignoreCase = true, limit = 2)
                s.trim() to extractKotlinVersion(rest)
            }

            rawStatus.contains(" in ", ignoreCase = true) -> {
                // Older proposals (e.g. KEEP-4) cram a free-form sentence after the version into
                // the same Status line: "Stable in 1.1 Discussion of this proposal is held in ...".
                // Split only at the first " in " (limit=2) and then extract just the version
                // prefix from the second piece, so implementedAt is always a valid Kotlin
                // version (or null) rather than a leftover sentence.
                val (s, rest) = rawStatus.split(" in ", ignoreCase = true, limit = 2)
                s.trim() to extractKotlinVersion(rest)
            }

            rawStatus.contains(" since ", ignoreCase = true) -> {
                val (s, rest) = rawStatus.split(" since ", ignoreCase = true, limit = 2)
                s.trim() to extractKotlinVersion(rest)
            }

            else -> rawStatus to null
        }

        return ProposalTextMetaData(
            proposalTextWithoutMetaData,
            title,
            metaMap["type"],
            author,
            metaMap["contributors"] ?: metaMap["proposal contributors"],
            status.lowercase(),
            implementedAt,
            metaMap["discussion"]
        )
    }

    private fun String.isLineAfterMetadata(): Boolean = this.startsWith("#")
            || this.startsWith(">")
            || this.startsWith("**Table of contents**", ignoreCase = true)

    /**
     * Extracts the leading Kotlin-version-shaped token from [text] (e.g. "1.1",
     * "1.4.0", "2.0.0-Beta", "2.1.20-RC2"). Returns null if [text] does not
     * start with such a token. Anything after the version (e.g. extraneous
     * prose accidentally pasted into the Status field) is discarded.
     */
    private fun extractKotlinVersion(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // Major(.Minor(.Patch)*) optionally followed by a -Pre / -RC / -Beta-N tag.
        val match = Regex("""^(\d+(?:\.\d+)+(?:[-+][\w.\-]+)?|\d+)\b""").find(trimmed) ?: return null
        return match.value
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
            .mapNotNull {
                val content = it.getJsonStringOrNull("body").orEmpty()
                val proposalId = content.extractProposalIdFromDiscussionContent()
                if (proposalId == null) {
                    val number = it["number"]?.jsonPrimitive?.intOrNull
                    log.warn(
                        "Skipping discussion #{}: body does not contain a https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-... link",
                        number,
                    )
                    return@mapNotNull null
                }
                DiscussionThread(
                    proposalId,
                    commentIds.nextId(),
                    it["number"]!!.jsonPrimitive.int,
                    it.getJsonString("title"),
                    it.getJsonStringOrNull("body").orEmpty(),
                    it["author"]!!.jsonObject.getJsonString("login"),
                    it.getJsonString("createdAt"),
                )
            }
            .toList()

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
                    discussionCommentJson.getJsonStringOrNull("body").orEmpty(),
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
                            replyJson.getJsonStringOrNull("body").orEmpty(),
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

    /**
     * Tries to find a `https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-NNNN-...md` URL in
     * the discussion body and returns the `KEEP-NNNN` prefix. Returns null if no such link is
     * present (some discussions in the `keep-discussions` category don't follow the convention).
     */
    private fun String.extractProposalIdFromDiscussionContent(): String? {
        val mentionedProposals = PROPOSAL_URL_REGEX.find(this)?.groupValues ?: return null
        assert(mentionedProposals.size == 2) { "Unexpected number of matches: $mentionedProposals" }
        return mentionedProposals[1]
    }


    private fun mapIssueComments(proposalIds: Set<Int>): List<Comment> {
        val prIssueCommentIssueNumber = readJsonlObjects(normalizedDir, "keep-pr-issuecomments")
            .map { it.getJsonString("_issue").toInt() }
            .distinct()

        val issueToIssueComments = readJsonlObjects(normalizedDir, "keep-issue-comments")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }   // GitHub comment IDs exceed Int range
            .groupBy { it.getJsonString("_issue").toInt() }

        return readJsonlObjects(normalizedDir, "keep-issues")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }   // GitHub issue IDs exceed Int range
            .filter {
                it["number"]!!.jsonPrimitive.int in proposalIds
            }.flatMap { issue ->
                val issueNumber = issue["number"]!!.jsonPrimitive.int
                val proposalId = "KEEP-" + issueNumber.toString().padStart(4, '0')
                val rootContent = issue.getJsonStringOrNull("body").orEmpty()
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
                            content = commentJson.getJsonStringOrNull("body").orEmpty(),
                        )
                    }

            }.toList()
    }

    companion object {
        private const val PROPOSALS_URL_PREFIX =
            "https://github.com/Kotlin/KEEP/blob/main/proposals/"
        private val PROPOSAL_URL_REGEX =
            Regex("""${Regex.escape(PROPOSALS_URL_PREFIX)}(?:stdlib/)?(KEEP-\d{4})""")
    }
}
