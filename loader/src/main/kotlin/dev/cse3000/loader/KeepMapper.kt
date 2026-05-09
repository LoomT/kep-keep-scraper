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

        val proposalIntIds = proposals.map { it.proposalId.toInt() }.toSet()
        val proposalIds = proposals.map { it.proposalId }.toSet()

        val comments = mapComments(proposalIntIds)

        val rawRelated = proposalGroups.flatMap { it.relatedProposals }
        val (validRelated, danglingRelated) = rawRelated.partition { it.proposalId in proposalIds }
        for (r in danglingRelated.distinctBy { it.proposalId to it.relatedProposalId }) {
            log.error(
                "Dropping RelatedProposal({}, supersedes, {}): superseder '{}' not in our proposal set",
                r.proposalId, r.relatedProposalId, r.proposalId,
            )
        }
        val relatedProposals = validRelated.distinct()

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
            relatedProposals = relatedProposals,
            comments = comments,
        )

        log.info("Meta keys seen: {}", proposalTextMetaKeysSeen)
        log.info(
            "Resolver counts: GH logins={}, emails={}, names={}, total Person rows={}",
            personByGHLogin.size, personByEmail.size, personByName.size, persons.size,
        )
        log.info(
            "Proposal status mapping: rawStatus -> normalizedStatus distinct pairs = {}",
            rows.stageHistory.map { it.rawStatus to it.normalizedStatus }.distinct(),
        )

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

        return proposalGroupedCommits.map { proposalCommits ->
            val pathName = proposalCommits.first.removePrefix("proposals/").removePrefix("stdlib/").take(9)
            assert(pathName.startsWith("KEEP-")) {
                "Proposal path name should start with KEEP-, got '$pathName' for path '${proposalCommits.first}'"
            }
            val proposalId = pathName.removePrefix("KEEP-")
            assert(proposalId.all(Char::isDigit)) {
                "Proposal id should be 4 digits after KEEP-, got '$proposalId' for path '${proposalCommits.first}'"
            }

            val topics = proposalCommits.second.mapNotNull { it.proposalData.topic }
                .ifEmpty {
                    // decide on a general topic based on if it's an stdlib proposal or a regular one
                    if (proposalCommits.first.contains("proposals/stdlib/KEEP-"))
                        listOf("Standard Library API proposal")
                    else
                        listOf("Design proposal")
                }
            assert(topics.distinct().size == 1) {
                "Topic should not change across revisions in $proposalId, got ${topics.distinct()} (count=${topics.size})"
            }
            val topic = topics.distinct().single()

            val authorNames = proposalCommits.second.flatMap { it.proposalData.authors }
            if (authorNames.isEmpty()) log.warn("Author is missing in $proposalId")

            val proposal = Proposal(
                projectId = projectId,
                proposalId = proposalId,
                topic = topic,
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
                .distinctUntilChangedBy { it.proposalData.rawStatus }
                .map { proposalCommit ->
                    StageHistory(
                        projectId = projectId,
                        proposalId = proposalId,
                        stageIndex = proposalCommit.index,
                        normalizedStatus = proposalCommit.proposalData.normalizedStatus,
                        rawStatus = proposalCommit.proposalData.rawStatus,
                        createdAt = proposalCommit.commitedAt,
                    )
                }

            val proposalAuthorRevisions = proposalCommits.second.flatMap { proposalCommit ->
                proposalCommit.proposalData.authors.distinct().map { name ->
                    ProposalRevisionAuthor(
                        projectId = projectId,
                        proposalId = proposalId,
                        revisionIndex = proposalCommit.index,
                        authorId = resolveName(name),
                    )
                }
            }

            val rawStatuses = proposalCommits.second.map { it.proposalData.rawStatus }.distinct()
            val supersederIds = rawStatuses
                .flatMap { extractSupersedingProposalIds(it) }
                .distinct()

            if (supersederIds.contains(proposalId))
                log.warn("Proposal $proposalId has itself as a superseder, ignoring")

            val relatedProposals = supersederIds
                .filter { it != proposalId }
                .map { supersederId ->
                    RelatedProposal(
                        projectId = projectId,
                        proposalId = supersederId,
                        relatedProjectId = projectId,
                        relatedProposalId = proposalId,
                        type = "supersedes",
                    )
                }

            ProposalGroup(
                proposal,
                proposalRevisions,
                proposalAuthorRevisions,
                stages,
                relatedProposals,
            )
        }
    }

    /**
     * Pulls all `KEEP-NNNN` references from a `"Superseded by …"` status string and returns
     * each as the bare 4-digit proposal_id (e.g. `"0367"`, not `"KEEP-0367"`). Returns an
     * empty list if the status doesn't contain "superseded" (case-insensitive). Handles both
     * markdown-link forms (`[KEEP-0367](./KEEP-0367-context-parameters.md)`) and plain text
     * (`Superseded by KEEP-0367 and KEEP-0500`).
     */
    private fun extractSupersedingProposalIds(rawStatus: String?): List<String> {
        if (rawStatus == null) return emptyList()
        if (!rawStatus.contains("superseded", ignoreCase = true) &&
            !rawStatus.contains("superceded", ignoreCase = true)
        ) return emptyList()
        return SUPERSEDING_KEEP_REGEX.findAll(rawStatus).map { it.groupValues[1] }.toList().distinct()
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
        val authors: List<String>,
        val rawStatus: String?,
        val normalizedStatus: String,
        val implementedAt: String?,
        val discussionLink: String?,
    )

    private data class ProposalGroup(
        val proposal: Proposal,
        val revisions: List<ProposalRevision>,
        val authorRevisions: List<ProposalRevisionAuthor>,
        val stages: List<StageHistory>,
        val relatedProposals: List<RelatedProposal>,
    )

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

        // Authors come from exactly one of the three meta keys: singular `Author`, plural
        // `Authors` (comma-separated list), or `Proposal Author`.
        val authorKeysPresent = listOf("author", "authors", "proposal author").filter { metaMap[it] != null }
        assert(authorKeysPresent.size <= 1) {
            "Multiple author-related fields $authorKeysPresent in $metaWithTitle"
        }
        val authors = authorKeysPresent.firstOrNull()?.let { metaMap[it] }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        if (authors == null) log.warn("Author field missing in {}", metaWithTitle)

        if (metaMap["status"] == null) log.warn("Status field missing in {}", metaWithTitle)

        val rawStatus = metaMap["status"]
        val (statusToken, implementedAt) = splitStatusFromVersion(rawStatus)

        return ProposalTextMetaData(
            proposalTextWithoutMetaData,
            title,
            metaMap["type"],
            authors.orEmpty(),
            rawStatus = rawStatus,
            normalizedStatus = normalizeStatus(statusToken),
            implementedAt,
            metaMap["discussion"]
        )
    }

    /**
     * Maps a parsed status token (e.g. `"Stable"`, `"Implemented"`,
     * `"Submitted"`, `"Superseded by KEEP-N"`) onto one of the `StageHistory.normalised_status`
     * CHECK enum values: `accepted`, `rejected`, `draft`, `review`, `withdrawn`, `unknown`.
     *
     * Per `schema.sql`: `superseded -> rejected, null or not clear -> unknown`.
     *
     * Logs a WARN with `MISSING_STATUS_MAPPING:` on any token that isn't recognized so they're
     * easy to grep out of the run output and add cases for.
     *
     * TODO: when new statuses appear in the WARN log, decide which bucket they belong in
     * and extend this match. Some current ambiguities flagged inline.
     */
    private fun normalizeStatus(token: String): String {
        // Strip surrounding punctuation / markdown bold markers / backticks; some KEEPs write
        // `* **Status**: ** In progress` (extra leading **) or wrap the whole status in **bold**.
        val k = token.lowercase()
            .trim()
            .trim('.', ',', ';', ':', '*', '`', ' ', '"', '\'')
            .trim()
        return when {
            k.isBlank() || k == "unknown" || k == "tbd" || k == "n/a" -> "unknown"

            // Accepted: shipped (with or without an experimental flag), or approved for shipping.
            k.contains("stable") -> "accepted"
            k.contains("implemented") -> "accepted"
            k.contains("experimental") -> "accepted"
            k == "accepted" || k.startsWith("accepted ") -> "accepted"
            k == "approved" || k.startsWith("approved ") -> "accepted"
            k == "published" || k.startsWith("published ") -> "accepted"
            k.contains("preview") -> "accepted" // released as Preview is still "shipped"
            k.startsWith("available") -> "accepted" // "Available in 2.2.20 under -X..." flag

            // Rejected: explicitly rejected, OR superseded by a newer proposal.
            k.contains("superseded") || k.contains("superceded") -> "rejected" // typo seen in the wild
            k.contains("rejected") -> "rejected"
            k.contains("declined") -> "rejected"

            // Withdrawn: author or maintainers stopped pursuing it.
            k.contains("withdrawn") -> "withdrawn"
            k.contains("abandoned") -> "withdrawn"
            // TODO confirm whether "Deprecated" / "Obsolete" should be `withdrawn` or `rejected` for KEEPs.
            k.contains("deprecated") -> "withdrawn"
            k.contains("obsolete") -> "withdrawn"

            // Review: actively under discussion / iteration. Catch-all on "discussion" + "review"
            // covers KEEP variants like "Public Discussion", "Internal Discussion", "Design
            // discussion", "Under discussion", "KEEP discussion", "Internal Review", "Design
            // review", and the plain "Review".
            k.contains("in progress") -> "review"
            k.contains("in design") -> "review"
            k.contains("discussion") || k.contains("discussing") -> "review"
            k.contains("review") -> "review"
            k.contains("under consideration") -> "review"
            k.contains("working on") -> "review" // "Working on the implementation"
            // TODO confirm "Prototyped"/"Prototype available" — currently mapping to
            // "review" since the proposal isn't done yet, but it could arguably be "accepted"
            // if a prototype binary has shipped.
            k.contains("prototype") || k.contains("prototyped") -> "review"

            // Draft: filed but not yet through review.
            k.contains("submitted") -> "draft"
            k.contains("proposed") -> "draft"
            k == "draft" || k.startsWith("draft ") -> "draft"
            k == "design" || k == "design proposal" -> "draft"

            else -> {
                log.warn(
                    "MISSING_STATUS_MAPPING: '{}' (raw token); mapping to 'unknown'. Add a case in normalizeStatus.",
                    token
                )
                "unknown"
            }
        }
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

    /**
     * Tries to split a raw Status field into (statusToken, kotlinVersion). Common KEEP forms:
     *
     *   "Stable in 1.1"                               -> ("Stable", "1.1")
     *   "Implemented in Kotlin 1.4.0"                 -> ("Implemented", "1.4.0")
     *   "Experimental since Kotlin 1.7.0"             -> ("Experimental", "1.7.0")
     *   "Stable in 1.1 Discussion of this proposal …" -> ("Stable", "1.1")  (junk discarded)
     *   "** In progress"                              -> ("** In progress", null)  (no version!)
     *   "Public Discussion"                           -> ("Public Discussion", null)
     *   "Superseded by [KEEP-0455](…)"                -> ("Superseded by [KEEP-0455](…)", null)
     */
    private fun splitStatusFromVersion(rawStatus: String?): Pair<String, String?> {
        if (rawStatus == null) return "Unknown" to null
        if (rawStatus.contains("superseded", ignoreCase = true)) return rawStatus to null

        // Try separators in order of specificity. Only commit to a split if the post-part is a
        // Kotlin version; otherwise " in " / " since " is part of the status name itself.
        val separators = listOf(" since Kotlin ", " in Kotlin ", " in ", " since ")
        for (sep in separators) {
            if (rawStatus.contains(sep, ignoreCase = true)) {
                val (s, rest) = rawStatus.split(sep, ignoreCase = true, limit = 2)
                val ver = extractKotlinVersion(rest)
                if (ver != null) return s.trim() to ver
            }
        }
        return rawStatus to null
    }

    private fun mapComments(proposalIntIds: Set<Int>): List<Comment> {
        val issueComments = mapIssueComments(proposalIntIds)
        val discussionComments = mapDiscussions()
        val reviewThreadComments = mapPrReviewThreadComments(proposalIntIds)
        return issueComments + discussionComments + reviewThreadComments
    }

    /**
     * PR code-review comments (line comments on diffs). Source: `keep-pr-review-comments.jsonl`,
     * one comment per line with `id`, `_pr`, `user.login`, `created_at`, `body`.
     *
     * Within each PR, every comment is chained chronologically: the earliest comment is the
     * root (`commentOnCommentId = null`) and every subsequent comment points at its
     * predecessor by `created_at`. Same shape as `mapIssueComments`, just keyed off the PR's
     * review-comment stream. The `in_reply_to_id` GitHub field is ignored — we keep one chain
     * per PR rather than per review thread.
     */
    private fun mapPrReviewThreadComments(proposalIntIds: Set<Int>): List<Comment> {
        val byPr = readJsonlObjects(normalizedDir, "keep-pr-review-comments")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .filter { it["_pr"]!!.jsonPrimitive.content.toInt() in proposalIntIds }
            .groupBy { it["_pr"]!!.jsonPrimitive.content.toInt() }

        return byPr.flatMap { (prNumber, prComments) ->
            val proposalId = prNumber.toString().padStart(4, '0')
            val sorted = prComments.sortedBy { it.getJsonString("created_at") }
            if (sorted.isEmpty()) return@flatMap emptyList()

            val rootJson = sorted.first()
            val rootComment = Comment(
                commentId = commentIds.nextId(),
                authorId = resolveGHLogin(rootJson["user"]!!.jsonObject.getJsonString("login")),
                projectId = projectId,
                proposalId = proposalId,
                commentOnCommentId = null,
                createdAt = rootJson.getJsonString("created_at"),
                content = rootJson.getJsonStringOrNull("body").orEmpty(),
            )
            sorted.drop(1).runningFold(rootComment) { previous, json ->
                Comment(
                    commentId = commentIds.nextId(),
                    authorId = resolveGHLogin(json["user"]!!.jsonObject.getJsonString("login")),
                    projectId = projectId,
                    proposalId = proposalId,
                    commentOnCommentId = previous.commentId,
                    createdAt = json.getJsonString("created_at"),
                    content = json.getJsonStringOrNull("body").orEmpty(),
                )
            }
        }
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


    private fun mapIssueComments(proposalIntIds: Set<Int>): List<Comment> {
        // Some KEEP proposals are tracked as a PR rather than a separate issue. For those, the
        // discussion thread lives in `keep-pr-issuecomments` rather than `keep-issue-comments`,
        // and the "issue" entry in `keep-issues.jsonl` is GitHub's PR-as-issue projection (which
        // is fine — it carries `number`, `body`, `user`, `created_at`, just like a real issue).
        // Merge both comment streams into one lookup; dedupe by id (comment id is globally unique
        // across both streams).
        val issueToIssueComments = (
                readJsonlObjects(normalizedDir, "keep-issue-comments") +
                        readJsonlObjects(normalizedDir, "keep-pr-issuecomments")
                )
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }   // GitHub comment IDs exceed Int range
            .groupBy { it.getJsonString("_issue").toInt() }

        return readJsonlObjects(normalizedDir, "keep-issues")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }   // GitHub issue IDs exceed Int range
            .filter {
                it["number"]!!.jsonPrimitive.int in proposalIntIds
            }.flatMap { issue ->
                val issueNumber = issue["number"]!!.jsonPrimitive.int
                val proposalId = issueNumber.toString().padStart(4, '0')
                val rootContent = issue.getJsonStringOrNull("body").orEmpty()
                val rootCreatedAt = issue.getJsonString("created_at")
                val rootLogin = issue["user"]!!.jsonObject.getJsonString("login")
                val rootAuthorId = resolveGHLogin(rootLogin)

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
            Regex("""${Regex.escape(PROPOSALS_URL_PREFIX)}(?:stdlib/)?KEEP-(\d{4})""")
        private val SUPERSEDING_KEEP_REGEX = Regex("""KEEP-(\d{4})""")
    }
}
