package dev.cse3000.loader

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(CommonMapper::class.java)

/**
 * Most-frequent git author/committer `name` observed alongside each email, and the
 * same tally keyed by GitHub login. Produced by [CommonMapper.populateCommitterAuthorEmails];
 * fed into the persons / personUsernames row build to choose a display name when the
 * GitHub profile doesn't supply one.
 */
data class CommitEnrichment(
    val gitNameByEmail: Map<String, String>,
    val gitFullNameByLogin: Map<String, String>,
)

object CommonMapper {
    /** Git author/committer role keys present on every GitHub commit object. */
    private val COMMIT_ROLES = listOf("author", "committer")

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

    /**
     * Populates [personByEmail] from self-declared emails in `<usersStream>.jsonl`
     * (one row per GitHub user). Only users whose `login` is already known
     * (i.e. in [personByGHLogin]) are considered — this is a *post-resolution
     * enrichment*, not an entry point for new persons. Should be called after
     * the personByGHLogin set is finalised (proposal authors + comment authors
     * + commit-derived logins).
     */
    fun populateUserEmails(
        normalizedDir: Path,
        usersStream: String,
        personByGHLogin: Map<String, Long>,
        personByEmail: MutableMap<String, Long>,
    ) {
        readJsonlObjects(normalizedDir, usersStream)
            .keepLatestScrapesBy { it.getJsonString("login") }
            .filter { it.getJsonString("login") in personByGHLogin.keys }
            .filter { it.getJsonStringOrNull("email") != null }
            .forEach { userJson ->
                val login = userJson.getJsonString("login")
                val email = userJson.getJsonString("email")
                personByEmail[email] = personByGHLogin.getValue(login)
            }
    }

    /**
     * Walks one or more `*-commits.jsonl` streams (e.g. `keep-commits`,
     * `keep-pr-commits` for KEEP — or `kep-commits`, `kep-pr-commits` for KEP) and,
     * for every commit role (author / committer) where GitHub successfully resolved
     * the git email to a GitHub `login`, links the git `name` and `email` to that
     * login's person row.
     *
     * - **Email → person**: added to [personByEmail] under the login's id, so the
     *   build-persons loop emits a `PersonUsername(domain="email", username=<email>,
     *   real_name=<most-popular name for this email>)` row attached to the same person
     *   as the github.com username row. First link wins for the *email→person* mapping —
     *   once an email is claimed by a login, a later commit with a different login on
     *   the same email is ignored. (Names are tallied independently and the popular
     *   pick wins regardless of which commit linked the email first.)
     * - **Per-email name tally** and **per-login name tally**: built locally and reduced
     *   to the most-frequent name. Multiple variants are kept so we don't lock to a
     *   one-off typo — e.g., "Mike Smith" (50 commits) wins over "Michael Smith" (1).
     *
     * Commits where GitHub couldn't match the email back to a login (`author`/`committer`
     * is `null` in the JSON) are skipped.
     *
     * [resolveGHLogin] is invoked for every commit's GH login, so a login first observed
     * in a commit (rather than in the proposal text / comments) gets a new person id
     * here. The conflict diagnostic uses [personByGHLogin] for a reverse lookup; pass
     * the same backing map that [resolveGHLogin] mutates so it stays in sync.
     */
    fun populateCommitterAuthorEmails(
        commitStreams: List<Sequence<JsonObject>>,
        personByGHLogin: Map<String, Long>,
        personByEmail: MutableMap<String, Long>,
        resolveGHLogin: (String) -> Long,
    ): CommitEnrichment {
        val nameCountsByEmail = mutableMapOf<String, MutableMap<String, Int>>()
        val nameCountsByLogin = mutableMapOf<String, MutableMap<String, Int>>()
        var processed = 0
        var roleRecordsWithLogin = 0
        var newEmailLinks = 0
        for (stream in commitStreams) {
            for (commit in stream) {
                processed++
                val gitCommit = commit["commit"] as? JsonObject ?: continue
                for (role in COMMIT_ROLES) {
                    val gitInfo = gitCommit[role] as? JsonObject ?: continue
                    val ghLogin = (commit[role] as? JsonObject)
                        ?.getJsonStringOrNull("login")
                        ?.takeIf { it.isNotBlank() } ?: continue
                    val gitName = gitInfo.getJsonStringOrNull("name")
                        ?.replace(".", " ") // some names use a dot instead of space
                        ?.takeIf { it.isNotBlank() }
                    val gitEmail = gitInfo.getJsonStringOrNull("email")
                        ?.takeIf { it.isNotBlank() }?.lowercase()

                    roleRecordsWithLogin++
                    val personId = resolveGHLogin(ghLogin)

                    if (personByEmail[gitEmail] != null && personByEmail[gitEmail] != personId) {
                        val existingPersonId = personByEmail[gitEmail]!!
                        val existingLogin = personByGHLogin.entries.find { it.value == existingPersonId }?.key
                        log.warn(
                            """
                            Duplicate email-to-login mapping for $gitEmail:
                                - existing: $existingLogin
                                - new: $ghLogin
                            Skipping commit: $commit
                            """
                        )
                    }

                    if (gitEmail != null && personByEmail.putIfAbsent(gitEmail, personId) == null) {
                        newEmailLinks++
                    }
                    if (gitEmail != null && gitName != null) {
                        nameCountsByEmail.getOrPut(gitEmail) { mutableMapOf() }
                            .merge(gitName, 1) { a, b -> a + b }
                    }
                    if (gitName != null) {
                        nameCountsByLogin.getOrPut(ghLogin) { mutableMapOf() }
                            .merge(gitName, 1) { a, b -> a + b }
                    }
                }
            }
        }

        val gitNameByEmail: Map<String, String> = nameCountsByEmail.mapValues { (_, counts) ->
            counts.maxByOrNull { it.value }!!.key
        }
        val gitFullNameByLogin: Map<String, String> = nameCountsByLogin.mapValues { (_, counts) ->
            counts.maxByOrNull { it.value }!!.key
        }

        log.info(
            "Commit enrichment: scanned {} commits ({} author/committer records with login); " +
                    "newly linked {} emails to GH logins; {} logins now have a git name tally",
            processed, roleRecordsWithLogin, newEmailLinks, gitFullNameByLogin.size,
        )
        return CommitEnrichment(gitNameByEmail, gitFullNameByLogin)
    }

    /**
     * Associates GitHub logins from [personByGHLogin] with their self-declared
     * display names from `<usersStream>.jsonl`. Users with blank `name` fields are
     * skipped (the fallback in the persons build is the most-frequent git
     * author/committer name from [CommitEnrichment.gitFullNameByLogin]).
     */
    fun ghLoginsToNames(
        normalizedDir: Path,
        usersStream: String,
        personByGHLogin: Map<String, Long>,
    ): Map<String, String> =
        readJsonlObjects(normalizedDir, usersStream)
            .keepLatestScrapesBy { it.getJsonString("login") }
            .filter { it.getJsonString("login") in personByGHLogin.keys }
            .filterNot { it.getJsonStringOrNull("name").isNullOrBlank() }
            .associate { userJson ->
                val login = userJson.getJsonString("login")
                val name = userJson.getJsonString("name")
                login to name
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
     * intentionally conservative; downstream RQ3 dedup can run a fuzzier merge if desired.)
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
        personByGHLogin: Map<String, Long>,
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

        val orgIdByCanonName = mutableMapOf<String, Long>()
        val organisations = mutableListOf<Organisation>()

        fun ensureOrg(rawName: String): Long? {
            val display = rawName.trim().removePrefix("@").trim()
            if (display.isEmpty()) return null
            val canon = display.lowercase()
            return orgIdByCanonName.getOrPut(canon) {
                val id = organisationIds.nextId()
                organisations += Organisation(organisationId = id, organisationName = display)
                id
            }
        }

        val affiliationKeys = mutableSetOf<Pair<Long, Long>>()
        val affiliations = mutableListOf<Affiliation>()
        fun addAffiliation(orgId: Long?, personId: Long) {
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
