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
    private val proposalsByPerson = mutableMapOf<Long, MutableSet<String>>()

    private val proposalTextMetaKeysSeen = mutableSetOf<String>()

    fun mapAll(): Rows {
        val proposalGroups = mapProposals()
        val proposals = proposalGroups.map { it.proposal }

        val proposalIntIds = proposals.map { it.proposalId.toInt() }.toSet()
        val proposalIds = proposals.map { it.proposalId }.toSet()

        val manualDiscussionToProposalsMap = mapOf(462 to setOf("0446", "0447"), 464 to setOf("0412"))

        val discussionToProposalsMap = proposalGroups
            .mapNotNull { group -> group.discussionId?.let { it to setOf(group.proposal.proposalId) } }
            .toMap()
            .plus(manualDiscussionToProposalsMap)

        log.info("Manually hardcoded discussion-proposals mappings: {}", manualDiscussionToProposalsMap)

        val prToProposalMap = proposalGroups
            .mapNotNull { group -> group.prId?.let { it to group.proposal.proposalId } }
            .toMap()
        val comments = mapComments(proposalIntIds, discussionToProposalsMap, prToProposalMap)

        val rawRelated = proposalGroups.flatMap { it.relatedProposals }
        val (relatedProposals, danglingRelatedCount) = CommonMapper.processRelatedProposals(rawRelated, proposalIds)

        CommonMapper.populateUserEmails(
            normalizedDir = normalizedDir,
            usersStream = "keep-users",
            personByGHLogin = personByGHLogin,
            personByEmail = personByEmail,
        )
        val commitStreams = listOf(
            readJsonlObjects(normalizedDir, "keep-commits").keepLatestScrapesBy {
                it.getJsonString("_path") + ":" + it.getJsonString("sha")
            },
            readJsonlObjects(normalizedDir, "keep-pr-commits").keepLatestScrapesBy {
                it.getJsonString("sha")
            }
        )
        val (gitNameByEmail, gitFullNameByLogin) = CommonMapper.populateCommitterAuthorEmails(
            commitStreams = commitStreams,
            personByGHLogin = personByGHLogin,
            personByEmail = personByEmail,
            resolveGHLogin = ::resolveGHLogin,
        )
        val ghLoginsToNames = CommonMapper.ghLoginsToNames(
            normalizedDir = normalizedDir,
            usersStream = "keep-users",
            personByGHLogin = personByGHLogin,
        )

        // === Resolve proposal-meta-text authors (currently in personByName) to GH logins or
        // git emails, but ONLY when the same name appears as a git author/committer on a
        // commit to that proposal's markdown — strong evidence the proposal-author actually
        // committed to their own proposal. Direction: collapse the login/email-anchored
        // person INTO the name-only person (which keeps the proposal-author's display name
        // canonical and matches the IdAllocator order — name-only ids were allocated first).
        // Runs after every other person enrichment so personByGHLogin / personByEmail are
        // fully populated; the resulting `updatedPersonIdMap` is applied transitively below.
        val updatedPersonIdMap = mutableMapOf<Long, Long>()
        val claimedLogins = mutableSetOf<String>()
        val claimedEmails = mutableSetOf<String>()
        val loginOrEmailByProposalAuthor = buildLoginOrEmailByProposalAuthor()
        var resolutionConflicts = 0

        for ((name, nameOnlyPersonId) in personByName) {
            val nameKey = name.replace(".", " ").lowercase()
            val proposalIds = proposalsByPerson[nameOnlyPersonId] ?: continue
            for (proposalId in proposalIds) {
                val (ghLogin, gitEmail) = loginOrEmailByProposalAuthor[proposalId to nameKey] ?: continue
                when {
                    ghLogin != null -> {
                        if (ghLogin in claimedLogins) {
                            // Already linked to a different name-only person in a prior iteration.
                            if (personByGHLogin[ghLogin] != nameOnlyPersonId) {
                                log.warn(
                                    "Conflict: GH login '{}' already linked to person {}; can't also link to name-only person {} ('{}', proposal {})",
                                    ghLogin, personByGHLogin[ghLogin], nameOnlyPersonId, name, proposalId,
                                )
                                resolutionConflicts++
                            }
                            continue
                        }
                        claimedLogins += ghLogin
                        val oldLoginId = personByGHLogin[ghLogin]
                        if (oldLoginId == nameOnlyPersonId) continue
                        personByGHLogin[ghLogin] = nameOnlyPersonId
                        if (oldLoginId != null) updatedPersonIdMap[oldLoginId] = nameOnlyPersonId
                    }

                    gitEmail != null -> {
                        if (gitEmail in claimedEmails) {
                            if (personByEmail[gitEmail] != nameOnlyPersonId) {
                                log.warn(
                                    "Conflict: email '{}' already linked to person {}; can't also link to name-only person {} ('{}', proposal {})",
                                    gitEmail, personByEmail[gitEmail], nameOnlyPersonId, name, proposalId,
                                )
                                resolutionConflicts++
                            }
                            continue
                        }
                        claimedEmails += gitEmail
                        val oldEmailId = personByEmail[gitEmail]
                        if (oldEmailId == nameOnlyPersonId) continue
                        personByEmail[gitEmail] = nameOnlyPersonId
                        if (oldEmailId != null) updatedPersonIdMap[oldEmailId] = nameOnlyPersonId
                    }
                }
            }
        }

        // Transitive closure of the substitution map. Defensive: if A→B and B→C ever co-exist
        // (shouldn't with the claim-set guards above, but cheap to compute), follow the chain.
        val closedSub: Map<Long, Long> = buildMap {
            for (k in updatedPersonIdMap.keys) {
                var cur = k
                val seen = mutableSetOf<Long>()
                while (cur in updatedPersonIdMap && seen.add(cur)) cur = updatedPersonIdMap[cur]!!
                put(k, cur)
            }
        }
        val sub: (Long) -> Long = { id -> closedSub[id] ?: id }

        // Apply the closure to every map so later reads (mapOrgsAndAffiliations, the persons
        // build below, and the comments substitution) all see canonical ids. In particular,
        // emails registered to an old login id by populateUserEmails/populateCommitterAuthor-
        // Emails get rewritten to the canonical name-only id here.
        for (k in personByGHLogin.keys.toList()) personByGHLogin[k] = sub(personByGHLogin[k]!!)
        for (k in personByEmail.keys.toList()) personByEmail[k] = sub(personByEmail[k]!!)
        for (k in personByName.keys.toList()) personByName[k] = sub(personByName[k]!!)

        log.info(
            "Proposal author resolution: {} login/email-anchored persons merged into name-only persons; {} conflicts skipped",
            updatedPersonIdMap.size, resolutionConflicts,
        )

        val (organisations, affiliations) = CommonMapper.mapOrgsAndAffiliations(
            normalizedDir = normalizedDir,
            orgsStream = "keep-orgs",
            usersStream = "keep-users",
            userOrgsStream = "keep-user-orgs",
            personByGHLogin = personByGHLogin,
            organisationIds = organisationIds,
        )

        val persons = mutableListOf<Person>()
        val personUsernames = mutableListOf<PersonUsername>()
        val emittedPersonIds = mutableSetOf<Long>()
        fun emitPerson(id: Long, fullName: String?) {
            if (emittedPersonIds.add(id)) persons += Person(personId = id, fullName = fullName)
        }

        for ((name, id) in personByName) {
            emitPerson(id, name)
        }
        for ((login, id) in personByGHLogin) {
            emitPerson(id, null)
            personUsernames += PersonUsername(
                personId = id,
                domain = "github.com",
                username = login,
                realName = ghLoginsToNames[login] ?: gitFullNameByLogin[login],
            )
        }
        for ((email, id) in personByEmail) {
            emitPerson(id, null) // fullName already supplied by the 2 loops above
            personUsernames += PersonUsername(
                personId = id,
                domain = "email",
                username = email,
                realName = gitNameByEmail[email],
            )
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
            organisations = organisations,
            affiliations = affiliations,
            proposals = proposals,
            proposalRevisions = proposalGroups.flatMap { it.revisions },
            proposalRevisionAuthors = proposalGroups.flatMap { it.authorRevisions }
                .map { it.copy(authorId = sub(it.authorId)) },
            stageHistory = proposalGroups.flatMap { it.stages },
            relatedProposals = relatedProposals,
            comments = comments.map { it.copy(authorId = sub(it.authorId)) },
        )

        log.info("KEEP meta keys seen: {}", proposalTextMetaKeysSeen)
        log.info(
            "KEEP resolver counts: proposals={}, revisions={}, " +
                    "related={}, dangling-related-dropped={}, " +
                    "comments={}, " +
                    "persons={} (GH logins={}, emails={}, names={}), " +
                    "Organisations={}, Affiliations={}",
            rows.proposals.size, rows.proposalRevisions.size,
            rows.relatedProposals.size, danglingRelatedCount,
            rows.comments.size,
            rows.persons.size, personByGHLogin.size, personByEmail.size, personByName.size,
            organisations.size, affiliations.size,
        )
        log.info(
            "KEEP status mapping: rawStatus -> normalizedStatus distinct pairs = {}",
            rows.stageHistory.map { it.rawStatus to it.normalizedStatus }.distinct(),
        )

        return rows
    }

    private fun String.proposalPathToId(): String {
        val pathName = this.removePrefix("proposals/").removePrefix("stdlib/").take(9)
        assert(pathName.startsWith("KEEP-")) {
            "Proposal path name should start with KEEP-, got '$pathName' for path '${this}'"
        }
        val proposalId = pathName.removePrefix("KEEP-")
        assert(proposalId.all(Char::isDigit)) {
            "Proposal id should be 4 digits after KEEP-, got '$proposalId' for path '${this}'"
        }
        return proposalId
    }

    private fun mapProposals(): List<ProposalGroup> {
        val proposalGroupedCommits = readJsonlObjects(
            normalizedDir,
            "keep-proposal-revisions"
        ) // sorting already done in [dev.cse3000.gh.git.RevisionWalker.listPathsAtHead]
            .keepLatestScrapesBy { it.getJsonString("path") + ":" + it.getJsonString("commit_sha") }
            .groupBy { it.getJsonString("path") }
            .filterNot { it.key.contains("TEMPLATE.md") }
            .map { (path, jsons) ->
                path to jsons.mapIndexed { index, json ->
                    val parsed = try {
                        json.getJsonString("content_text").extractProposalTextMetaData(path.proposalPathToId(), index)
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
            val topics = proposalCommits.second.mapNotNull { it.proposalData.topic }
                .ifEmpty {
                    // decide on a general topic based on if it's an stdlib proposal or a regular one
                    if (proposalCommits.first.contains("proposals/stdlib/KEEP-"))
                        listOf("Standard Library API proposal")
                    else
                        listOf("Design proposal")
                }
            val proposalId = proposalCommits.first.proposalPathToId()
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
                        authorId = resolveAuthor(name, proposalId),
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

            val proposalDiscussionIds = proposalCommits.second.mapNotNull { it.proposalData.discussionId }.distinct()
            assert(proposalDiscussionIds.size <= 1) {
                "Proposal $proposalId has multiple discussions: $proposalDiscussionIds"
            }
            val discussionId = proposalDiscussionIds.singleOrNull()

            val proposalPrIds = proposalCommits.second.mapNotNull { it.proposalData.prId }.distinct()
            assert(proposalPrIds.size <= 1) {
                "Proposal $proposalId has multiple discussion PRs: $proposalPrIds"
            }
            val prId = proposalPrIds.singleOrNull()

            ProposalGroup(
                proposal,
                proposalRevisions,
                proposalAuthorRevisions,
                stages,
                relatedProposals,
                discussionId,
                prId,
            )
        }
    }

    /**
     * Best-effort `(proposalId, lowercased name) → (ghLogin, gitEmail)` map, derived from
     * `keep-commits.jsonl` (which contains commits scoped to proposal markdown files via
     * the `_path` meta). For every commit's author/committer, we record a "this name committed to this proposal"
     * entry — letting [mapProposals] upgrade matching proposal-meta-text authors from name-only
     * resolution to a real GH login or at least an email if GitHub account is not linked.
     *
     * On duplicate key conflict, keeps the earlier entry.
     */
    private fun buildLoginOrEmailByProposalAuthor(): Map<Pair<String, String>, Pair<String?, String?>> {
        val result = mutableMapOf<Pair<String, String>, Pair<String?, String?>>()
        val latestJsonlObjects = readJsonlObjects(normalizedDir, "keep-commits").keepLatestScrapesBy {
            it.getJsonString("_path") + ":" + it.getJsonString("sha")
        }
        for (commit in latestJsonlObjects) {
            val path = commit.getJsonString("_path")
            if (path.endsWith("TEMPLATE.md")) continue
            val proposalId = path.proposalPathToId()
            val gitCommit = commit["commit"] as? JsonObject ?: continue
            for (role in COMMIT_ROLES) {
                val gitInfo = gitCommit[role] as? JsonObject ?: continue
                val ghLogin = (commit[role] as? JsonObject)
                    ?.getJsonStringOrNull("login")
                    ?.takeIf { it.isNotBlank() }
                val gitEmail = gitInfo.getJsonStringOrNull("email")
                    ?.takeIf { it.isNotBlank() }
                if (ghLogin == null && gitEmail == null) continue
                val gitName = gitInfo.getJsonStringOrNull("name")
                    ?.replace(".", " ")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val existingPair = result[proposalId to gitName.lowercase()]
                if (existingPair == null) result[proposalId to gitName.lowercase()] = ghLogin to gitEmail
                else if (existingPair.first == null && ghLogin != null)
                    result[proposalId to gitName.lowercase()] = ghLogin to gitEmail
            }
        }
        return result
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
        val discussionId: Int?,
        val prId: Int?,
    )

    private data class ProposalGroup(
        val proposal: Proposal,
        val revisions: List<ProposalRevision>,
        val authorRevisions: List<ProposalRevisionAuthor>,
        val stages: List<StageHistory>,
        val relatedProposals: List<RelatedProposal>,
        val discussionId: Int?,
        val prId: Int?,
    )

    private fun resolveGHLogin(login: String): Long =
        personByGHLogin.getOrPut(login) { personIds.nextId() }

    private fun resolveAuthor(name: String, proposalId: String): Long {
        val personId = personByName.getOrPut(name) { personIds.nextId() }
        proposalsByPerson.getOrPut(personId) { mutableSetOf() }.add(proposalId)
        return personId
    }

    private fun String.extractProposalTextMetaData(proposalId: String, revisionIndex: Int): ProposalTextMetaData {
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
                log.warn(
                    "Meta line before any bullet entry in KEEP-{} ver-{}; ignoring: '{}'",
                    proposalId,
                    revisionIndex,
                    line
                )
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
            "duplicate keys $keys in KEEP-$proposalId ver-$revisionIndex: $metaWithTitle"
        }
        proposalTextMetaKeysSeen.addAll(keys)

        val metaMap = metaPairs.toMap()

        if (metaMap["type"] == null) {
            log.warn("Type field missing in KEEP-{} ver-{}", proposalId, revisionIndex)
            log.debug("Type field missing: {}", metaWithTitle)
        }

        // Authors come from exactly one of the three meta keys: singular `Author`, plural
        // `Authors` (comma-separated list), or `Proposal Author`.
        val authorKeysPresent = listOf("author", "authors", "proposal author").filter { metaMap[it] != null }
        assert(authorKeysPresent.size <= 1) {
            "Multiple author-related fields $authorKeysPresent in KEEP-$proposalId ver-$revisionIndex: $metaWithTitle"
        }
        val authors = authorKeysPresent.firstOrNull()?.let { metaMap[it] }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        if (authors == null) {
            log.warn("Author field missing in KEEP-{} ver-{}", proposalId, revisionIndex)
            log.debug("Author field missing: {}", metaWithTitle)
        }

        val rawStatus = metaMap["status"]
        if (rawStatus == null) {
            log.warn("Status field missing in KEEP-{} ver-{}", proposalId, revisionIndex)
            log.debug("Status field missing: {}", metaWithTitle)
        }
        val (statusToken, implementedAt) = splitStatusFromVersion(rawStatus)

        return ProposalTextMetaData(
            proposalTextWithoutMetaData,
            title,
            metaMap["type"],
            authors.orEmpty(),
            rawStatus = rawStatus,
            normalizedStatus = normalizeStatus(statusToken),
            implementedAt,
            metaMap["discussion"]?.extractDiscussionIdFromDiscussionField(),
            metaMap["discussion"]?.extractPrIdFromDiscussionField(),
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

    private fun mapComments(
        proposalIntIds: Set<Int>,
        discussionToProposalsMap: Map<Int, Set<String>>,
        prToProposalMap: Map<Int, String>
    ): List<Comment> {
        val issueComments = CommonMapper.mapIssueComments(
            normalizedDir = normalizedDir,
            issuesStream = "keep-issues",
            issueCommentsStream = "keep-issue-comments",
            projectId = projectId,
            commentIds = commentIds,
            issueNumberToProposalId = { n -> if (n in proposalIntIds) n.toString().padStart(4, '0') else null },
            resolveGHLogin = ::resolveGHLogin,
        )
        val discussionComments = mapDiscussions(discussionToProposalsMap)
        val reviewThreadComments = CommonMapper.mapPrComments(
            normalizedDir = normalizedDir,
            pullsStream = "keep-pulls",
            prIssueCommentsStream = "keep-pr-issuecomments",
            prReviewCommentsStream = "keep-pr-review-comments",
            projectId = projectId,
            commentIds = commentIds,
            prToProposalMap = prToProposalMap,
            resolveGHLogin = ::resolveGHLogin,
        )
        return issueComments + discussionComments + reviewThreadComments
    }

    private fun mapDiscussions(discussionToProposalsMap: Map<Int, Set<String>>): List<Comment> {
        val discussionCommentJsons = readJsonlObjects(normalizedDir, "keep-discussion-comments")
            .keepLatestScrapesBy { it.getJsonString("id") }

        val discussionThreads = readJsonlObjects(normalizedDir, "keep-discussions")
            .keepLatestScrapesBy { it.getJsonString("id") }
            .filter { it["category"]!!.jsonObject.getJsonString("name") == "keep-discussions" }
            .mapNotNull {
                val number = it["number"]!!.jsonPrimitive.int
                val proposalIds = discussionToProposalsMap[number]
                if (proposalIds.isNullOrEmpty()) {
                    log.warn(
                        "Skipping discussion #{}: no proposal links to this discussion",
                        number,
                    )
                    return@mapNotNull null
                }
                proposalIds.map { proposalId ->
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
            }
            .flatten()
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
     * Tries to find a `https://github.com/Kotlin/KEEP/discussions/<id>` URL in
     * the discussion field and returns the `<id>`.
     */
    private fun String.extractDiscussionIdFromDiscussionField(): Int? {
        val discussionLinks = DISCUSSION_URL_REGEX.find(this)?.groupValues ?: return null
        assert(discussionLinks.size == 2) { "Unexpected number of matches: $discussionLinks in $this" }
        return discussionLinks[1].toInt()
    }

    /**
     * Tries to find a `https://github.com/Kotlin/KEEP/discussions/<id>` URL in
     * the discussion field and returns the `<id>`.
     */
    private fun String.extractPrIdFromDiscussionField(): Int? {
        val prLinks = PR_URL_REGEX.find(this)?.groupValues ?: return null
        assert(prLinks.size == 2) { "Unexpected number of matches: $prLinks in $this" }
        return prLinks[1].toInt()
    }

    companion object {
        private val DISCUSSION_URL_REGEX =
            Regex("""https://github\.com/Kotlin/KEEP/discussions/(\d+)""")
        private val PR_URL_REGEX =
            Regex("""https://github\.com/Kotlin/KEEP/pull/(\d+)""")
        private val SUPERSEDING_KEEP_REGEX = Regex("""KEEP-(\d{4})""")

        private val COMMIT_ROLES = listOf("author", "committer")
    }
}
