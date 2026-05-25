package dev.cse3000.loader

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(CommonMapper::class.java)

/**
 * Per-person aggregates of git author observations. Produced by [CommonMapper.loginsToGitAuthors]:
 *
 * - [gitNamesByLogin] — every distinct git author `name` seen for each github login.
 * - [gitEmailsByLogin] — every distinct git author `email` seen for each github login.
 */
data class GitAuthorByLogin(
    val gitNamesByLogin: Map<String, Set<String>>,
    val gitEmailsByLogin: Map<String, Set<String>>
)

object CommonMapper {

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
     * [issueNumberToProposalIds] resolves a GitHub issue number to the set of proposal_ids
     * it belongs to. When a project has duplicate proposal numbers (e.g. `0412-0` and
     * `0412-1` after suffixing), one issue maps to multiple variants — a separate comment
     * chain is emitted for each, with fresh commentIds. Returning an empty set skips the
     * issue. Each project chooses its own rule — e.g., KEEP/KEP look up the bare issue
     * number in the suffixed-proposal-id set.
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
        issueNumberToProposalIds: (Int) -> Set<String>,
        resolveGHLogin: (String) -> Int,
    ): List<Comment> {
        val issueToIssueComments = readJsonlObjects(normalizedDir, issueCommentsStream)
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .groupBy { it.getJsonString("_issue").toInt() }

        return readJsonlObjects(normalizedDir, issuesStream)
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .flatMap { issue ->
                val issueNumber = issue["number"]!!.jsonPrimitive.int
                val proposalIds = issueNumberToProposalIds(issueNumber)
                if (proposalIds.isEmpty()) return@flatMap emptyList()

                proposalIds.flatMap { proposalId ->
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
            }
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
     * [prToProposalIds] is the project-specific PR-number → set-of-proposal_ids mapping.
     * One PR can map to multiple proposal variants when a project has duplicate proposal
     * numbers (e.g. KEEP-0412 split into `0412-0` and `0412-1`); a separate comment chain
     * is emitted per proposal_id, with fresh commentIds. PRs absent from this map (and any
     * comments scoped to them) are ignored.
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
        prToProposalIds: Map<Int, Set<String>>,
        resolveGHLogin: (String) -> Int,
    ): List<Comment> {
        val prBodies: Map<Int, JsonObject> = readJsonlObjects(normalizedDir, pullsStream)
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .filter { it["number"]!!.jsonPrimitive.int in prToProposalIds.keys }
            .associateBy { it["number"]!!.jsonPrimitive.int }

        val regularComments = readJsonlObjects(normalizedDir, prIssueCommentsStream)
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .filter { it["_issue"]!!.jsonPrimitive.int in prToProposalIds.keys }

        val reviewComments = readJsonlObjects(normalizedDir, prReviewCommentsStream)
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .filter { it["_pr"]!!.jsonPrimitive.int in prToProposalIds.keys }

        val byPr: Map<Int, List<JsonObject>> = (regularComments + reviewComments).groupBy {
            it["_pr"]?.jsonPrimitive?.intOrNull
                ?: it["_issue"]?.jsonPrimitive?.intOrNull
                ?: error("PR comment row has neither _pr nor _issue meta: $it")
        }

        return prToProposalIds.flatMap { (prNumber, proposalIds) ->
            val prJson = prBodies[prNumber]
            if (prJson == null) {
                log.warn(
                    "PR #{} not found in {}; skipping comment chain for proposals {}",
                    prNumber, pullsStream, proposalIds,
                )
                return@flatMap emptyList()
            }

            proposalIds.flatMap { proposalId ->
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
                val ghIdToAllocated = mutableMapOf<Long, Int>()
                val emitted = mutableListOf<Comment>()
                var lastTopLevel = rootComment

                for (json in sorted) {
                    val ghId = json["id"]!!.jsonPrimitive.long
                    val parentGhId = json["in_reply_to_id"]?.jsonPrimitive?.longOrNull
                    val parentAllocated: Int? = if (parentGhId == null) {
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

    /**
     * Walks one or more `*-commits.jsonl` streams (e.g. `keep-commits`,
     * `keep-pr-commits` for KEEP — or `kep-commits`, `kep-pr-commits` for KEP) and,
     * where GitHub successfully resolved the git commit author email to a GitHub `login`,
     * records the git author `name` and `email` for that login's person row.
     *
     * - **Email → person**: added to [personByGitEmail] under the login's id. First link
     *   wins for the *email→person* mapping — once an email is claimed by a login, a
     *   later commit with a different login on the same email is ignored (a conflict
     *   diagnostic is logged). Each entry becomes a
     *   `PersonIdentifier(domain="git_author", identifier_type="email", ...)` row.
     * - **Per-person name set** — every distinct `name` seen per personId is kept
     *   (the identifier is part of the PersonIdentifier PK, so all variants survive).
     *
     * Commits where GitHub couldn't match the email back to a login (`author` is
     * `null` in the JSON) are skipped.
     *
     * [resolveGHLogin] is invoked for every commit's GH login, so a login first observed
     * in a commit (rather than in the proposal text / comments) gets a new person id
     * here. The conflict diagnostic uses [personByGHLogin] for a reverse lookup; pass
     * the same backing map that [resolveGHLogin] mutates so it stays in sync.
     */
    fun loginsToGitAuthors(
        commitStreams: List<Sequence<JsonObject>>,
    ): GitAuthorByLogin {
        val namesByLogin = mutableMapOf<String, MutableSet<String>>()
        val emailsByLogin = mutableMapOf<String, MutableSet<String>>()
        var processed = 0
        var roleRecordsWithLogin = 0
        for (stream in commitStreams) {
            for (commit in stream) {
                processed++
                val gitCommit = commit["commit"] as? JsonObject ?: continue
                val gitInfo = gitCommit["author"] as? JsonObject ?: continue
                val ghLogin = (commit["author"] as? JsonObject)
                    ?.getJsonStringOrNull("login")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val gitName = gitInfo.getJsonStringOrNull("name")
                    ?.takeIf { it.isNotBlank() }
                val gitEmail = gitInfo.getJsonStringOrNull("email")
                    ?.takeIf { it.isNotBlank() }?.lowercase()

                roleRecordsWithLogin++

                if (gitName != null) {
                    namesByLogin.getOrPut(ghLogin) { mutableSetOf() }.add(gitName)
                }
                if (gitEmail != null) {
                    emailsByLogin.getOrPut(ghLogin) { mutableSetOf() }.add(gitEmail)
                }
            }
        }

        log.info(
            "Commit enrichment: scanned {} commits ({} author records with login)",
            processed, roleRecordsWithLogin,
        )
        return GitAuthorByLogin(namesByLogin, emailsByLogin)
    }

    /**
     * Associates GitHub logins from [personByGHLogin] with their self-declared
     * display names and emails from `<usersStream>.jsonl`.
     */
    fun loginsToNamesAndEmails(
        normalizedDir: Path,
        usersStream: String,
        personByGHLogin: Map<String, Int>,
    ): Map<String, Pair<String?, String?>> =
        readJsonlObjects(normalizedDir, usersStream)
            .keepLatestScrapesBy { it.getJsonString("login") }
            .filter { it.getJsonString("login") in personByGHLogin.keys }
            .associate { userJson ->
                val login = userJson.getJsonString("login")
                val name = userJson.getJsonStringOrNull("name")
                val email = userJson.getJsonStringOrNull("email")
                login to (name to email)
            }

    /**
     * Builds the [Organisation] and [Affiliation] rows from two sources:
     *
     * 1. **GitHub org membership** — `<userOrgsStream>.jsonl` (one row per user with their
     *    org list) joined against `<orgsStream>.jsonl` (full per-org details).
     * 2. **`user.company` free-text field** — from `<usersStream>.jsonl`. Stripped of leading
     *    `@` (people often write `@JetBrains`) and deduped against (1) by case-insensitive
     *    canonical name.
     *
     * Orgs are deduped by canonical name (lowercased, trimmed, leading `@` stripped) so
     * `@JetBrains`, `JetBrains`, the GitHub org `JetBrains` all end up as separate rows
     * ONLY if they differ after that normalisation. (They often will — string matching is
     * intentionally conservative; downstream analysis dedup can run a fuzzier merge if desired.)
     *
     * Mapping business email domains to organisations is also possible but intentionally
     * left to downstream RQs since the emails are available there anyway.
     *
     * Affiliations are deduped on the composite PK `(organisation_id, person_id)`.
     *
     * Should run after [personByGHLogin] is fully populated — users / userOrgs are
     * filtered to logins already in the set, so this never widens the person set.
     */
    fun mapOrgsAndAffiliations(
        normalizedDir: Path,
        orgsStream: String,
        usersStream: String,
        userOrgsStream: String,
        personByGHLogin: Map<String, Int>,
        organisationIds: IdAllocator,
    ): Pair<List<Organisation>, List<Affiliation>> {
        val orgsByLogin: Map<String, JsonObject> = readJsonlObjects(normalizedDir, orgsStream)
            .keepLatestScrapesBy { it.getJsonString("login") }
            .associateBy { it.getJsonString("login") }

        val users = readJsonlObjects(normalizedDir, usersStream)
            .keepLatestScrapesBy { it.getJsonString("login") }
            .filter { it.getJsonString("login") in personByGHLogin.keys }
            .toList()

        val userOrgs = readJsonlObjects(normalizedDir, userOrgsStream)
            .keepLatestScrapesBy { it.getJsonString("_login") }
            .filter { it.getJsonString("_login") in personByGHLogin.keys }
            .toList()

        val orgIdByCanonName = mutableMapOf<String, Int>()
        val organisations = mutableListOf<Organisation>()

        fun ensureOrg(rawName: String): Int? {
            val display = rawName.trim().removePrefix("@").trim()
            if (display.isEmpty()) return null
            val canon = display.lowercase()
            return orgIdByCanonName.getOrPut(canon) {
                val id = organisationIds.nextId()
                organisations += Organisation(organisationId = id, organisationName = display)
                id
            }
        }

        val affiliationKeys = mutableSetOf<Pair<Int, Int>>()
        val affiliations = mutableListOf<Affiliation>()
        fun addAffiliation(orgId: Int?, personId: Int) {
            if (orgId == null) return
            if (affiliationKeys.add(orgId to personId)) {
                affiliations += Affiliation(organisationId = orgId, personId = personId)
            }
        }

        // Source 1: explicit GitHub memberships.
        for (row in userOrgs) {
            val personId = personByGHLogin.getValue(row.getJsonString("_login"))
            val orgsArray = row["orgs"] as? JsonArray ?: continue
            for (ref in orgsArray) {
                val orgLogin = (ref as? JsonObject)?.getJsonStringOrNull("login") ?: continue
                // Prefer the org's display `name` (from /orgs/{org}); fall back to the login.
                val orgJson = orgsByLogin[orgLogin]
                val name = orgJson?.getJsonStringOrNull("name")?.takeIf { it.isNotBlank() } ?: orgLogin
                addAffiliation(ensureOrg(name), personId)
            }
        }

        // Source 2: company free-text.
        for (user in users) {
            val personId = personByGHLogin.getValue(user.getJsonString("login"))
            user.getJsonStringOrNull("company")?.takeIf { it.isNotBlank() }?.let { company ->
                addAffiliation(ensureOrg(company), personId)
            }
        }
        return organisations to affiliations
    }
}
