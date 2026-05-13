package dev.cse3000.loader

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(CommonMapper::class.java)

class CommonMapper(
    private val projectId: Int,
    private val normalizedDir: Path,
    private val personIds: IdAllocator,
    private val organisationIds: IdAllocator,
    private val commentIds: IdAllocator,
) {
    companion object {
        fun processRelatedProposals(
            rawRelated: List<RelatedProposal>,
            proposalIds: Set<String>
        ): Pair<List<RelatedProposal>, Int> {
            val (validRelated, danglingRelated) = rawRelated.partition {
                it.proposalId in proposalIds && it.relatedProposalId in proposalIds
            }
            for (r in danglingRelated.distinctBy { Triple(it.proposalId, it.type, it.relatedProposalId) }) {
                val side = when (r.proposalId) {
                    !in proposalIds if r.relatedProposalId !in proposalIds -> "both sides"
                    !in proposalIds -> "left side"
                    else -> "right side"
                }
                log.warn(
                    "Dropping RelatedProposal({}, {}, {}): $side not in our proposal set",
                    r.proposalId, r.type, r.relatedProposalId,
                )
            }
            // The schema PK on RelatedProposal is (proposal_id, related_proposal_id) — type is not
            // part of the key. When the same edge appears with both `supersedes` and `related`
            // (a KEP that lists another in *both* `replaces` and `see-also`), keep the stronger
            // edge: supersedes wins over related.
            val relatedProposals = validRelated
                .groupBy { Quadruple(it.projectId, it.proposalId, it.relatedProjectId, it.relatedProposalId) }
                .map { (_, group) ->
                    group.firstOrNull { it.type == "supersedes" } ?: group.first()
                }

            return relatedProposals to danglingRelated.size
        }

        /**
         * Maps a project's tracking-issue + issue-comments streams to a Comment chain per
         * proposal. The issue body itself becomes a root [Comment]; each comment is parented
         * to the previous in `created_at` order.
         *
         * [issueNumberToProposalId] resolves a GitHub issue number to its proposal_id (or
         * null to skip). Each project chooses its own rule — e.g., KEEP zero-pads issue
         * numbers to 4 digits, KEP uses the bare number.
         *
         * [resolveGHLogin] is a project-specific callback that interns a GH login into a
         * person_id (with whatever side-effects the project tracks, like adding to
         * personByGHLogin). It's called for the issue author and every comment author.
         */
        fun mapIssueComments(
            normalizedDir: Path,
            issuesStream: String,
            issueCommentsStream: String,
            projectId: Int,
            commentIds: IdAllocator,
            issueNumberToProposalId: (Int) -> String?,
            resolveGHLogin: (String) -> Long,
        ): List<Comment> {
            val issueToIssueComments = readJsonlObjects(normalizedDir, issueCommentsStream)
                .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
                .groupBy { it.getJsonString("_issue").toInt() }

            return readJsonlObjects(normalizedDir, issuesStream)
                .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
                .mapNotNull { issue ->
                    val issueNumber = issue["number"]!!.jsonPrimitive.int
                    val proposalId = issueNumberToProposalId(issueNumber) ?: return@mapNotNull null

                    val rootComment = Comment(
                        commentId = commentIds.nextId(),
                        authorId = resolveGHLogin(issue.getLoginOrGhost()),
                        projectId = projectId,
                        proposalId = proposalId,
                        commentOnCommentId = null,
                        createdAt = issue.getJsonString("created_at"),
                        content = issue.getJsonStringOrNull("body").orEmpty(),
                    )

                    val issueComments = issueToIssueComments[issueNumber] ?: emptyList()
                    issueComments.sortedBy { it.getJsonString("created_at") }
                        .runningFold(rootComment) { previous, commentJson ->
                            Comment(
                                commentId = commentIds.nextId(),
                                authorId = resolveGHLogin(commentJson.getLoginOrGhost()),
                                projectId = projectId,
                                proposalId = proposalId,
                                commentOnCommentId = previous.commentId,
                                createdAt = commentJson.getJsonString("created_at"),
                                content = commentJson.getJsonStringOrNull("body").orEmpty(),
                            )
                        }
                }
                .flatten()
                .toList()
        }

        /**
         * Maps every proposal-linked PR's body + comments into a Comment chain.
         *
         * Structure:
         * - **Root**: the PR's `body` itself (from [pullsStream]), parented to nothing.
         * - **Top-level comments** (regular PR conversation comments + review-comments with
         *   no `in_reply_to_id`) chain to one another by `created_at`; the first chains to
         *   the PR body. Replies are skipped over when computing the chain — the next
         *   top-level comment links to the previous *top-level* comment.
         * - **Replies** (`in_reply_to_id != null`, only on review comments — issue-style
         *   comments don't have replies in GitHub's model) hang off their parent via the
         *   `in_reply_to_id` value, looked up against an in-flight ghId → allocated id map.
         *   GitHub guarantees `in_reply_to_id` always references an earlier-created
         *   same-PR comment, so a created_at sort processes parents before their replies.
         *   Orphan replies (parent not in the scrape) are dropped with a WARN.
         *
         * [prToProposalMap] is the project-specific PR-number → proposal_id mapping. PRs
         * absent from this map (and any comments scoped to them) are ignored.
         *
         * [prIssueCommentsStream] is the "conversation tab" comments (issuecomments). Pass
         * an empty string-typed but nonexistent stream name when a project has no such
         * stream — `readJsonlObjects` returns an empty sequence and the function degrades
         * cleanly. [prReviewCommentsStream] is the "review/diff tab" comments (with
         * `in_reply_to_id`).
         */
        fun mapPrComments(
            normalizedDir: Path,
            pullsStream: String,
            prIssueCommentsStream: String,
            prReviewCommentsStream: String,
            projectId: Int,
            commentIds: IdAllocator,
            prToProposalMap: Map<Int, String>,
            resolveGHLogin: (String) -> Long,
        ): List<Comment> {
            val prBodies: Map<Int, JsonObject> = readJsonlObjects(normalizedDir, pullsStream)
                .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
                .filter { it["number"]!!.jsonPrimitive.int in prToProposalMap.keys }
                .associateBy { it["number"]!!.jsonPrimitive.int }

            val regularComments = readJsonlObjects(normalizedDir, prIssueCommentsStream)
                .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
                .filter { it["_issue"]!!.jsonPrimitive.int in prToProposalMap.keys }

            val reviewComments = readJsonlObjects(normalizedDir, prReviewCommentsStream)
                .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
                .filter { it["_pr"]!!.jsonPrimitive.int in prToProposalMap.keys }

            val byPr: Map<Int, List<JsonObject>> = (regularComments + reviewComments).groupBy {
                it["_pr"]?.jsonPrimitive?.intOrNull
                    ?: it["_issue"]?.jsonPrimitive?.intOrNull
                    ?: error("PR comment row has neither _pr nor _issue meta: $it")
            }

            return prToProposalMap.flatMap { (prNumber, proposalId) ->
                val prJson = prBodies[prNumber]
                if (prJson == null) {
                    log.warn(
                        "PR #{} not found in {}; skipping comment chain for proposal {}",
                        prNumber, pullsStream, proposalId,
                    )
                    return@flatMap emptyList()
                }

                val rootComment = Comment(
                    commentId = commentIds.nextId(),
                    authorId = resolveGHLogin(prJson.getLoginOrGhost()),
                    projectId = projectId,
                    proposalId = proposalId,
                    commentOnCommentId = null,
                    createdAt = prJson.getJsonString("created_at"),
                    content = prJson.getJsonStringOrNull("body").orEmpty(),
                )

                val sorted = byPr[prNumber].orEmpty().sortedBy { it.getJsonString("created_at") }
                val ghIdToAllocated = mutableMapOf<Long, Long>()
                val emitted = mutableListOf<Comment>()
                var lastTopLevel = rootComment

                for (json in sorted) {
                    val ghId = json["id"]!!.jsonPrimitive.long
                    val parentGhId = json["in_reply_to_id"]?.jsonPrimitive?.longOrNull
                    val parentAllocated: Long? = if (parentGhId == null) {
                        lastTopLevel.commentId
                    } else {
                        ghIdToAllocated[parentGhId] ?: run {
                            log.warn(
                                "Dropping review comment {} in PR #{}: in_reply_to_id={} not in same PR's data",
                                ghId, prNumber, parentGhId,
                            )
                            null
                        }
                    }
                    if (parentAllocated == null) continue

                    val comment = Comment(
                        commentId = commentIds.nextId(),
                        authorId = resolveGHLogin(json.getLoginOrGhost()),
                        projectId = projectId,
                        proposalId = proposalId,
                        commentOnCommentId = parentAllocated,
                        createdAt = json.getJsonString("created_at"),
                        content = json.getJsonStringOrNull("body").orEmpty(),
                    )
                    ghIdToAllocated[ghId] = comment.commentId
                    emitted += comment
                    // Only top-level comments advance the chain anchor. Replies hang off their
                    // own parent and don't displace the chain pointer.
                    if (parentGhId == null) lastTopLevel = comment
                }

                listOf(rootComment) + emitted
            }
        }
    }
}
