package dev.cse3000.loader

import com.charleskorn.kaml.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(KepMapper::class.java)

/**
 * Maps `kep-*.jsonl` streams (under [normalizedDir]) to typed [Rows] for the
 * KEP project.
 *
 * Each KEP lives in a leaf directory containing `kep.yaml` (structured metadata)
 * and `README.md` (the proposal body). The usual location is
 * `keps/<sig>/<number>-<slug>/`, though some sigs nest proposals one level deeper
 * (`keps/<sig>/<group>/<number>-<slug>/`). A single git commit usually touches
 * both files; we group revisions by the leaf directory, sort by commit time, and
 * pair each commit's yaml with its readme (carrying forward the prior side when
 * a commit touches only one).
 *
 * Early KEPs used a flat `keps/<sig>/<date>-<slug>.md` format with the yaml
 * embedded as front-matter. The upstream migration to the split layout was done
 * via `git mv` from the single `.md` to the new dir's `README.md`, so the
 * walker's rename-following surfaces those pre-migration commits under the
 * modern dir naturally — the per-commit `path` field reveals the historical
 * filename.
 *
 * `proposal_id` is the bare KEP number (e.g. `"2313"`). A handful of dirs
 * collide on this prefix (three `0000-*` and two `2133-*`); the first dir wins
 * and the rest are dropped with a WARN.
 */
class KepMapper(
    private val projectId: Int,
    private val normalizedDir: Path,
    private val personIds: IdAllocator,
    private val organisationIds: IdAllocator,
    private val commentIds: IdAllocator,
) {
    private val personByGHLogin = mutableMapOf<String, Int>()

    /**
     * Slug → bare proposal_id, populated during [mapProposals]. Used to resolve see-also /
     * replaces / superseded-by references whose value points at an old-style date-prefixed
     * or unprefixed `.md` file rather than the modern numeric dir.
     */
    private val proposalIdBySlug = mutableMapOf<String, String>()

    fun mapAll(): Rows {
        val proposalGroups = mapProposals()
        val proposals = proposalGroups.map { it.proposal }
        val proposalIds = proposals.map { it.proposalId }.toSet()

        // Bare int → all suffixed proposalIds. KEP proposal_ids are bare numbers like "9"
        // or "2451"; collisions get -0/-1/... suffixes in mapProposals. The corresponding
        // GitHub tracking issue (when present) has the bare number, so issue lookup uses
        // the bare int and may resolve to multiple suffixed variants.
        val proposalIdsByBareInt: Map<Int, Set<String>> = proposals
            .mapNotNull { p ->
                val bare = p.proposalId.substringBefore('-').toIntOrNull()
                if (bare != null) bare to p.proposalId else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet() }

        fun expandBareToAllSuffixed(bareOrSuffixed: String): Set<String> {
            if ('-' in bareOrSuffixed) return setOf(bareOrSuffixed) // already specific
            val bareInt = bareOrSuffixed.toIntOrNull() ?: return setOf(bareOrSuffixed)
            return proposalIdsByBareInt[bareInt] ?: setOf(bareOrSuffixed)
        }

        val rawRelated = proposalGroups.flatMap { it.relatedProposals }
            .flatMap { r ->
                // canonicaliseKepRef may emit either a suffixed id (slug-resolved) or a
                // bare number (leading-number fallback). Expand bare ids to all matching
                // variants so collisions don't silently drop edges.
                val lefts = expandBareToAllSuffixed(r.proposalId)
                val rights = expandBareToAllSuffixed(r.relatedProposalId)
                lefts.flatMap { l -> rights.map { rr -> r.copy(proposalId = l, relatedProposalId = rr) } }
            }
            .filter { it.proposalId != it.relatedProposalId }
        val (relatedProposals, danglingRelatedCount) = CommonMapper.processRelatedProposals(rawRelated, proposalIds)

        val prToProposalIds = buildPrToProposalIds(proposalIdsByBareInt)

        val issueComments = CommonMapper.mapIssueComments(
            normalizedDir = normalizedDir,
            issuesStream = "kep-issues",
            issueCommentsStream = "kep-issue-comments",
            projectId = projectId,
            commentIds = commentIds,
            issueNumberToProposalIds = { n -> proposalIdsByBareInt[n] ?: emptySet() },
            resolveGHLogin = ::resolveGHLogin,
        )
        val prComments = CommonMapper.mapPrComments(
            normalizedDir = normalizedDir,
            pullsStream = "kep-pulls",
            prIssueCommentsStream = "kep-pr-issuecomments",
            prReviewCommentsStream = "kep-pr-review-comments",
            projectId = projectId,
            commentIds = commentIds,
            prToProposalIds = prToProposalIds,
            resolveGHLogin = ::resolveGHLogin,
        )
        val comments = issueComments + prComments

        val commitStreams = listOf(
            readJsonlObjects(normalizedDir, "kep-commits").keepLatestScrapesBy {
                it.getJsonString("sha")
            },
            readJsonlObjects(normalizedDir, "kep-pr-commits").keepLatestScrapesBy {
                it.getJsonString("sha")
            }
        )
        val commitGitAuthorByLogin = CommonMapper.loginsToGitAuthors(commitStreams)
        val ghNameAndEmailByLogin = CommonMapper.loginsToNamesAndEmails(
            normalizedDir = normalizedDir,
            usersStream = "kep-users",
            personByGHLogin = personByGHLogin,
        )
        val (organisations, affiliations) = CommonMapper.mapOrgsAndAffiliations(
            normalizedDir = normalizedDir,
            orgsStream = "kep-orgs",
            usersStream = "kep-users",
            userOrgsStream = "kep-user-orgs",
            personByGHLogin = personByGHLogin,
            organisationIds = organisationIds,
        )

        val persons = mutableListOf<Person>()
        val personIdentifiers = mutableListOf<PersonIdentifier>()
        val emittedPersonIds = mutableSetOf<Int>()
        fun emitPerson(id: Int) {
            if (emittedPersonIds.add(id)) persons += Person(personId = id, fullName = null)
        }

        // Person.full_name is left null — KEP directly uses GitHub users as identifiers for authors in proposals.
        for ((login, id) in personByGHLogin) {
            emitPerson(id)
            personIdentifiers += PersonIdentifier(id, "github.com", "username", login)
            val nameAndEmailByLogin = ghNameAndEmailByLogin[login]
            nameAndEmailByLogin?.first?.let {
                personIdentifiers += PersonIdentifier(id, "github.com", "display_name", it)
            }
            nameAndEmailByLogin?.second?.let {
                personIdentifiers += PersonIdentifier(id, "github.com", "email", it)
            }
            for (gitName in commitGitAuthorByLogin.gitNamesByLogin[login].orEmpty()) {
                personIdentifiers += PersonIdentifier(id, "git_author", "username", gitName)
            }
            for (gitEmail in commitGitAuthorByLogin.gitEmailsByLogin[login].orEmpty()) {
                personIdentifiers += PersonIdentifier(id, "git_author", "email", gitEmail)
            }
        }

        val rows = Rows(
            projects = listOf(
                Project(
                    projectId = projectId,
                    projectName = "Kubernetes",
                    enhancementProposalName = "KEP",
                    copyright = "Apache-2.0",
                ),
            ),
            persons = persons,
            personIdentifiers = personIdentifiers,
            organisations = organisations,
            affiliations = affiliations,
            proposals = proposals,
            proposalRevisions = proposalGroups.flatMap { it.revisions },
            proposalRevisionAuthors = proposalGroups.flatMap { it.authorRevisions },
            proposalStatuses = proposalGroups.flatMap { it.statusRevisions },
            relatedProposals = relatedProposals,
            comments = comments,
        )

        log.info(
            "KEP resolver counts: proposals={}, revisions={}, related={}, dangling-related-dropped={}, " +
                    "persons={} (GH logins={}), " +
                    "organisations={}, affiliations={}, comments={}",
            rows.proposals.size, rows.proposalRevisions.size, rows.relatedProposals.size, danglingRelatedCount,
            rows.persons.size, personByGHLogin.size,
            rows.organisations.size, rows.affiliations.size, rows.comments.size,
        )
        log.info(
            "KEP status mapping: rawStatus -> normalizedStatus distinct pairs = {}",
            rows.proposalStatuses.map { it.rawStatus to it.normalisedStatus }.distinct(),
        )

        return rows
    }

    private fun mapProposals(): List<ProposalGroup> {
        // Every KEP at HEAD lives in a leaf directory containing `kep.yaml` +
        // `README.md`. The usual layout is `keps/<sig>/<NNNN>-<slug>/`, but some
        // proposals nest deeper under the sig (e.g. `keps/<sig>/<group>/<NNNN>-<slug>/`).
        // Either way, the head dir uniquely identifies one logical proposal, so we
        // group on it. Pre-migration single-file `.md`s no longer exist at HEAD
        // (leftover ones are one-line redirect stubs filtered by `isKepFile`); their
        // history surfaces here as older commits whose per-commit `path` is the old
        // single-file form, attached to the modern README's dir via the `git mv`
        // that rename-following crosses.
        val byKey: Map<String, List<RawCommit>> = RevisionGrouping
            .readGroupedByHeadDir(normalizedDir, "kep-proposal-revisions")
            .mapValues { (_, rows) -> rows.toRawCommits() }

        // Pass 1: assign each key a bare proposal_id (kep-number from yaml when present,
        // else the leading number from the dir/file name). Disambiguate collisions by
        // appending `-0`, `-1`, ... so every variant gets a unique PK. Both the suffixed
        // final id and the slug → final-id map are populated.
        val barePerKey = mutableMapOf<String, String>()
        for ((key, rawCommits) in byKey) {
            val lastCommitWithYaml = rawCommits.lastOrNull { it.yaml != null }
            val meta = lastCommitWithYaml?.yaml?.let { parseYamlMeta(it, key, lastCommitWithYaml.commitSha) }
            val id = meta?.kepNumber ?: key.proposalIdFromKey() ?: continue
            barePerKey[key] = id
        }
        val keyToProposalId = mutableMapOf<String, String>()
        for ((bareId, entries) in barePerKey.entries.groupBy { it.value }) {
            if (entries.size == 1) {
                val key = entries.single().key
                keyToProposalId[key] = bareId
                proposalIdBySlug[extractSlug(key.lastSegment().removeMdSuffix())] = bareId
            } else {
                log.info(
                    "KEP-{} proposal_id collision: {} variants — suffixing as {}-0..{}-{}",
                    bareId, entries.size, bareId, bareId, entries.size - 1,
                )
                entries.sortedBy { it.key }.forEachIndexed { idx, entry ->
                    val finalId = "$bareId-$idx"
                    keyToProposalId[entry.key] = finalId
                    proposalIdBySlug[extractSlug(entry.key.lastSegment().removeMdSuffix())] = finalId
                }
            }
        }

        // Pass 2: build the ProposalGroups using the now-populated proposalIdBySlug.
        return byKey.mapNotNull { (key, rawCommits) ->
            val proposalId = keyToProposalId[key] ?: return@mapNotNull null
            buildProposalGroup(key, proposalId, rawCommits)
        }
    }

    /**
     * Checks whether a per-commit historical `path` looks like a pre-migration
     * single-file KEP (`keps/<sig>/<dateOrSlug>.md`). HEAD no longer has any of
     * these — but the rename-aware walk surfaces them in older commits as the
     * pre-`git mv` predecessor of a modern dir's `README.md`.
     */
    private fun isPreMigrationSingleFile(path: String): Boolean =
        path.startsWith("keps/") &&
                path.endsWith(".md", ignoreCase = true) &&
                !path.endsWith("README.md", ignoreCase = true) &&
                path.count { it == '/' } == 2

    private fun String.lastSegment(): String = substringAfterLast('/', this)

    private fun String.removeMdSuffix(): String =
        removeSuffix(".md").removeSuffix(".MD")

    /**
     * Resolves a proposal id from a head-dir grouping key (`keps/<sig>/<NNNN>-<slug>`):
     * the leading number on the final segment.
     */
    private fun String.proposalIdFromKey(): String? =
        LEADING_KEP_NUMBER_REGEX.find(lastSegment())?.groupValues?.get(1)

    /**
     * Folds a flat list of rename-aware revision rows (one per commit × file
     * touched) into [RawCommit]s. Rows within the same commit are combined into a
     * single RawCommit; the per-commit historical `path` selects which slot the
     * row populates (yaml, readme, or old-format single-file).
     */
    private fun List<JsonObject>.toRawCommits(): List<RawCommit> =
        groupBy { it.getJsonString("commit_sha") }
            .map { (sha, group) ->
                val yaml = group.find { it.getJsonString("path").endsWith("kep.yaml", ignoreCase = true) }
                val readme = group.find { it.getJsonString("path").endsWith("README.md", ignoreCase = true) }
                val oldFormat = group.find { isPreMigrationSingleFile(it.getJsonString("path")) }
                RawCommit(
                    commitSha = sha,
                    committedAt = group.first().getJsonString("committed_at"),
                    yaml = yaml,
                    readme = readme,
                    oldFormat = oldFormat,
                )
            }
            .sortedBy { it.committedAt }

    /**
     * Folds a key's raw commit list into [ProposalRevision]s. Revisions start at the first
     * commit that has a body (readme OR old-format `.md`); pre-migration commits emit
     * body-only revisions (no yaml meta). Once yaml appears it's carried forward; readme
     * likewise. Old-format and readme bodies aren't carried into each other — the migration
     * commit usually adds the readme in the same commit, so the discontinuity is one
     * revision at most. The `key` parameter is either a modern split-layout dir or the
     * path of an unmigrated single-file KEP.
     */
    private fun buildProposalGroup(key: String, proposalId: String, rawCommits: List<RawCommit>): ProposalGroup? {
        val extracted = rawCommits.map { it to extractYamlAndBody(it) }
        val firstBodyIndex = extracted.indexOfFirst { it.second.second != null }
        if (firstBodyIndex < 0) {
            log.error("Skipping {}: no commit had body content (readme/old-format)", key)
            return null
        }

        val (firstRc, firstYb) = extracted[firstBodyIndex]
        val folded: List<FoldedCommit> = extracted.drop(firstBodyIndex + 1).runningFold(
            FoldedCommit(firstRc.commitSha, firstRc.committedAt, firstYb.first, firstYb.second!!)
        ) { prev, (rc, yb) ->
            FoldedCommit(
                commitSha = rc.commitSha,
                committedAt = rc.committedAt,
                yamlContent = yb.first ?: prev.yamlContent,
                body = yb.second ?: prev.body,
            )
        }

        val revisions = mutableListOf<ProposalRevision>()
        val authorRevisions = mutableListOf<ProposalRevisionAuthor>()
        val metas = mutableListOf<KepYamlMeta>()

        val titleFallback = key.lastSegment().removeMdSuffix()
        for ((index, fc) in folded.withIndex()) {
            val meta =
                fc.yamlContent?.let { parseYamlMetaFromContent(it, "$key@${fc.commitSha.take(8)}") } ?: EMPTY_META
            metas += meta
            revisions += ProposalRevision(
                projectId = projectId,
                proposalId = proposalId,
                revisionIndex = index,
                title = meta.title ?: titleFallback,
                createdAt = fc.committedAt,
                content = fc.body,
                implementedAtVersion = meta.implementedAtVersion,
            )
            for (login in meta.authors.distinct()) {
                authorRevisions += ProposalRevisionAuthor(
                    projectId = projectId,
                    proposalId = proposalId,
                    revisionIndex = index,
                    authorId = resolveGHLogin(login),
                )
            }
        }

        // Topic: prefer the yaml's owning-sig (most descriptive once stripped of the "sig-"
        // prefix). Fallback: pick the `sig-*` segment from the key (or provider-aws).
        val topic = metas.firstNotNullOfOrNull { it.owningSig }?.removePrefix("sig-")
            ?: key.split('/').firstOrNull { it.startsWith("sig-") }?.removePrefix("sig-")
            ?: key.removePrefix("keps/").substringBefore('/', "").takeIf { it.isNotEmpty() }
            ?: throw AssertionError("All topic fallbacks failed for path: $key")

        val proposal = Proposal(
            projectId = projectId,
            proposalId = proposalId,
            topic = topic,
        )

        // Emit a ProposalStatus row whenever the raw status changes between consecutive
        // revisions (matching the KEEP semantics).
        val statusRevisions = folded.zip(metas)
            .foldIndexed(mutableListOf<ProposalStatus>()) { idx, acc, (fc, meta) ->
                if (meta.rawStatus != null && (acc.isEmpty() || acc.last().rawStatus != meta.rawStatus)) {
                    acc += ProposalStatus(
                        projectId = projectId,
                        proposalId = proposalId,
                        statusIndex = idx,
                        rawStatus = meta.rawStatus,
                        normalisedStatus = meta.normalizedStatus,
                        createdAt = fc.committedAt,
                    )
                }
                acc
            }

        val replaces = metas.flatMap { it.replaces }.distinct()
        val supersededBy = metas.flatMap { it.supersededBy }.distinct()
        val seeAlso = metas.flatMap { it.seeAlso }.distinct()
        val related = buildList {
            for (other in replaces) if (other != proposalId) {
                // This proposal replaces `other` → we are the superseder.
                add(
                    RelatedProposal(
                        projectId = projectId,
                        proposalId = proposalId,
                        relatedProjectId = projectId,
                        relatedProposalId = other,
                        type = "supersedes",
                    )
                )
            }
            for (other in supersededBy) if (other != proposalId) {
                // `other` supersedes this one.
                add(
                    RelatedProposal(
                        projectId = projectId,
                        proposalId = other,
                        relatedProjectId = projectId,
                        relatedProposalId = proposalId,
                        type = "supersedes",
                    )
                )
            }
            for (other in seeAlso) if (other != proposalId) {
                // see-also is symmetric in spirit, but the schema only stores a directed edge —
                // we emit one in the (this -> other) direction.
                add(
                    RelatedProposal(
                        projectId = projectId,
                        proposalId = proposalId,
                        relatedProjectId = projectId,
                        relatedProposalId = other,
                        type = "related",
                    )
                )
            }
        }

        return ProposalGroup(
            proposal = proposal,
            revisions = revisions,
            authorRevisions = authorRevisions,
            statusRevisions = statusRevisions,
            relatedProposals = related,
        )
    }

    private fun parseYamlMeta(yamlJson: JsonObject, dir: String, commitSha: String): KepYamlMeta =
        parseYamlMetaFromContent(yamlJson.getJsonString("content_text"), "$dir@${commitSha.take(8)}")

    /**
     * Returns (yamlContentOrNull, bodyContentOrNull) for a raw commit. New-format commits
     * map directly to their (yaml, readme) fields. Old-format commits split the `.md`
     * content into front-matter (delimited by `---` lines) and the body — early KEPs
     * embedded the yaml fields at the top of the markdown file.
     */
    private fun extractYamlAndBody(rc: RawCommit): Pair<String?, String?> {
        rc.oldFormat?.let { json ->
            val (front, body) = splitFrontMatter(json.getJsonString("content_text"))
            return front to body
        }
        return rc.yaml?.getJsonString("content_text") to rc.readme?.getJsonString("content_text")
    }

    /**
     * Splits an old-format `.md` content into front-matter yaml and body. Returns
     * `(null, content)` when no `---`-delimited block is present at the start.
     */
    private fun splitFrontMatter(content: String): Pair<String?, String> {
        val match = FRONT_MATTER_REGEX.matchAt(content, 0) ?: return null to content
        val front = match.groupValues[1]
        val body = content.substring(match.range.last + 1)
        return front to body
    }

    private fun parseYamlMetaFromContent(content: String, label: String): KepYamlMeta {
        // Some KEP yamls have unquoted `@login` scalars (e.g. `- @liggitt`), which kaml rejects
        // because `@` is a reserved YAML indicator. Quote those before parsing.
        val sanitised = content.replace(UNQUOTED_AT_REGEX, "$1\"@$2\"")
        val parsed: YamlNode? = runCatching { Yaml.default.parseToYamlNode(sanitised) }
            .onFailure {
                if (log.isDebugEnabled) log.warn("yaml parse failed for {}: {}", label, it.message)
                else log.warn("yaml parse failed for {}", label)
            }
            .getOrNull()
        val map = (parsed as? YamlMap) ?: return EMPTY_META

        val title = map.scalarOrNull("title")
        val kepNumber = map.scalarOrNull("kep-number")
        if (kepNumber == "NNNN") return EMPTY_META // copied yaml template
        val owningSig = map.scalarOrNull("owning-sig")
        val rawStatus = map.scalarOrNull("status")
        val statusToken = rawStatus?.substringBefore("#")?.trim()?.takeIf { it.isNotBlank() }
        val authors = map.scalarListOrEmpty("authors")
            .map { it.removePrefix("@").trim() }
            .filter { it.isNotBlank() && it != "TBD" && it != "N/A" && it != "NA" }
            .filter { it != "jane.doe" } // template placeholder author

        val milestone = runCatching { map.get<YamlMap>("milestone") }.getOrNull()
        val stableMilestone = milestone?.scalarOrNull("stable")
        val latestMilestone = map.scalarOrNull("latest-milestone")
        val isImplemented = statusToken?.lowercase()?.startsWith("implemented") == true
        val implementedAtVersion = if (isImplemented) {
            (stableMilestone ?: latestMilestone)?.takeIf { it != "TBD" && it != "N/A" }
        } else null

        val replaces = map.scalarListOrEmpty("replaces").mapNotNull { canonicaliseKepRef(it) }
        val supersededBy = map.scalarListOrEmpty("superseded-by").mapNotNull { canonicaliseKepRef(it) }
        val seeAlso = map.scalarListOrEmpty("see-also").mapNotNull { canonicaliseKepRef(it) }

        return KepYamlMeta(
            title = title,
            kepNumber = kepNumber,
            authors = authors,
            owningSig = owningSig,
            rawStatus = rawStatus,
            normalizedStatus = normalizeStatus(statusToken),
            implementedAtVersion = implementedAtVersion,
            replaces = replaces,
            supersededBy = supersededBy,
            seeAlso = seeAlso,
        )
    }

    /**
     * Maps a parsed KEP status token onto one of the `ProposalStatus.normalised_status`
     * CHECK enum values: `accepted`, `rejected`, `draft`, `review`, `withdrawn`, `superseded`, `unknown`.
     *
     * KEP's canonical states are `provisional`, `implementable`, `implemented`, `deferred`,
     * `rejected`, `withdrawn`, `replaced` — we additionally see typos (`imlpemented`,
     * `implementeable`), the literal template string `provisional|implementable|…`, and a
     * few one-offs (`alpha`, `removed`, `superseded`).
     *
     * Logs a WARN with `MISSING_STATUS_MAPPING:` on any token that isn't recognized so they're
     * easy to grep out of the run output and add cases for.
     *
     * TODO: when new statuses appear in the WARN log, decide which bucket they belong in and extend this match.
     */
    private fun normalizeStatus(token: String?): String {
        if (token.isNullOrBlank()) return "unknown"
        val k = token.lowercase()
            .trim()
            .trim('.', ',', ';', ':', '*', '`', ' ', '"', '\'')
            .trim()
        return when {
            k.isBlank() || k == "unknown" || k == "tbd" || k == "n/a" || k == "nnnn" -> "unknown"
            // Literal template placeholder `provisional|implementable|…` — treat as unset.
            k.startsWith("provisional|") -> "unknown"

            // Accepted: shipped/stable.
            k.startsWith("implemented") || k.startsWith("imlpemented") -> "accepted"

            // Rejected / superseded.
            k.startsWith("rejected") -> "rejected"
            k.startsWith("replaced") -> "rejected"

            // Withdrawn / deferred / removed.
            k.startsWith("withdrawn") -> "withdrawn"
            k.startsWith("deferred") -> "withdrawn"
            k.startsWith("removed") -> "withdrawn"

            // Review: implementable + in-flight alpha/beta.
            k.startsWith("implementable") || k.startsWith("implementeable") || k.startsWith("implementables") -> "review"
            k == "alpha" || k == "beta" -> "review"

            // Draft: provisional / proposed.
            k.startsWith("provisional") -> "draft"
            k.startsWith("proposed") -> "draft"
            k == "draft" || k.startsWith("draft ") -> "draft"

            // Superseded: explicitly superseded by a newer proposal.
            k.startsWith("superseded") -> "superseded"

            else -> {
                log.warn(
                    "MISSING_STATUS_MAPPING: '{}' (raw token); mapping to 'unknown'. Add a case in normalizeStatus.",
                    token
                )
                "unknown"
            }
        }
    }

    private fun resolveGHLogin(login: String): Int =
        personByGHLogin.getOrPut(login) { personIds.nextId() }

    /**
     * Scans `kep-pulls.jsonl` and links each PR to its referenced KEP via the title
     * (e.g. "KEP-1234", "kep 2345", "KEP1234"). PRs whose title mentions multiple *distinct*
     * KEP numbers are dropped as ambiguous; PRs whose title mentions a single number that
     * happens to have collision-suffixed variants emit a comment chain for every variant.
     */
    private fun buildPrToProposalIds(proposalIdsByBareInt: Map<Int, Set<String>>): Map<Int, Set<String>> {
        var titled = 0
        val bareNumbersByPr = mutableMapOf<Int, MutableSet<Int>>()
        readJsonlObjects(normalizedDir, "kep-pulls")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .forEach { pull ->
                val title = pull.getJsonStringOrNull("title") ?: return@forEach
                val matches = KEP_IN_TITLE_REGEX.findAll(title)
                if (matches.none()) return@forEach
                titled++
                val knownBares = matches
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .filter { it in proposalIdsByBareInt.keys }
                    .toSet()
                if (knownBares.isEmpty()) return@forEach
                val prNumber = pull["number"]!!.jsonPrimitive.int
                bareNumbersByPr.getOrPut(prNumber) { mutableSetOf() }.addAll(knownBares)
            }

        val (singleBare, multiBare) = bareNumbersByPr.entries.partition { it.value.size == 1 }
        val validMap = singleBare.associate { entry ->
            val bare = entry.value.single()
            entry.key to (proposalIdsByBareInt[bare] ?: emptySet())
        }

        log.info(
            "KEP PR→proposal title match: {} PRs mentioned a KEP number; " +
                    "{} resolved to a known proposal ({} total proposal variants, accounting for " +
                    "duplicate-id suffixing); {} resolved to multiple distinct KEPs (ignoring).",
            titled, validMap.size, validMap.values.sumOf { it.size }, multiBare.size,
        )
        return validMap
    }

    /**
     * Pulls a final proposal_id (e.g. `"2451"` or `"0000-1"`) out of a `replaces` /
     * `superseded-by` / `see-also` reference. KEP yaml authors use these fields very
     * loosely — values range from clean repo-relative paths to external URLs to literal
     * `"TBD"`.
     *
     * Resolution order:
     * 1. **Slug lookup** — for any modern (`NNNN-<slug>`) or old-format (`<date>-<slug>`,
     *    `<slug>`) path, strip the numeric prefix and look up the bare slug in
     *    [proposalIdBySlug]. This is preferred over the leading-number form because
     *    when two distinct proposals share a number (collision suffixing), the slug
     *    uniquely identifies which variant the reference points at.
     * 2. **Leading-number fallback** — when the slug doesn't match (the referenced KEP
     *    isn't in our scrape, or the path was missing/numeric-only), return the bare
     *    `NNNN`. Callers expand bare ids to all variants when there are duplicates.
     * 3. Otherwise null.
     */
    private fun canonicaliseKepRef(ref: String): String? {
        val trimmed = ref.trim().trim('(', ')', ',', ' ', '"', '\'').trim()
        if (trimmed.isEmpty()) return null
        val keepOnly = trimmed.substringBefore('#').substringBefore(' ').trim()
        val pathMatch = KEPS_PATH_REGEX.find(keepOnly) ?: return null
        val segment = pathMatch.groupValues[1]
            .removeSuffix("/")
            .removeSuffix(".md")
            .removeSuffix(".MD")
            .removeSuffix("/README")

        // Slug-based lookup first — picks the right variant when a bare number has multiple.
        val slug = extractSlug(segment)
        proposalIdBySlug[slug]?.let { return it }

        // Leading-number fallback for cases where the slug isn't in our set.
        val leadingNumber = LEADING_KEP_NUMBER_REGEX.find(segment)?.groupValues?.get(1)
        if (leadingNumber != null && leadingNumber.length <= 5) {
            return if (leadingNumber !in TEMPLATE_KEP_NUMBERS) leadingNumber else null
        }
        return null
    }

    private data class RawCommit(
        val commitSha: String,
        val committedAt: String,
        val yaml: JsonObject?,
        val readme: JsonObject?,
        val oldFormat: JsonObject?,
    )

    private data class FoldedCommit(
        val commitSha: String,
        val committedAt: String,
        val yamlContent: String?,
        val body: String,
    )

    private data class KepYamlMeta(
        val title: String?,
        val kepNumber: String?,
        val authors: List<String>,
        val owningSig: String?,
        val rawStatus: String?,
        val normalizedStatus: String,
        val implementedAtVersion: String?,
        val replaces: List<String>,
        val supersededBy: List<String>,
        val seeAlso: List<String>,
    )

    private data class ProposalGroup(
        val proposal: Proposal,
        val revisions: List<ProposalRevision>,
        val authorRevisions: List<ProposalRevisionAuthor>,
        val statusRevisions: List<ProposalStatus>,
        val relatedProposals: List<RelatedProposal>,
    )

    companion object {
        /**
         * Matches a `KEP-NNNN` / `kep NNNN` / `KEP1234` reference inside free-form text
         * (case-insensitive). Used to attribute a PR to a proposal via its title.
         */
        private val KEP_IN_TITLE_REGEX = Regex("""(?i)\bkep[\s-]?(\d{1,5})\b""")

        /** Matches an in-repo KEP directory reference inside a free-text yaml value. */
        private val KEPS_PATH_REGEX = Regex("""/?keps/[^/\s]+/([^\s)]+(?:/README\.md)?)""")

        /** Matches a leading numeric prefix (`NNNN` or `NNNN-`) on a path segment. */
        private val LEADING_KEP_NUMBER_REGEX = Regex("""^(\d{1,5})(?:-|$)""")

        /** Matches an unquoted `@login` scalar after a YAML list dash. */
        private val UNQUOTED_AT_REGEX = Regex("""(?m)^(\s*-\s+)@([\w.-]+)\s*$""")

        /**
         * Matches a leading `---`-delimited front-matter block. Group 1 is the yaml body
         * between the fences. The closing `---` must be on its own line.
         */
        private val FRONT_MATTER_REGEX = Regex(
            """---\s*\r?\n(.*?)\r?\n---\s*(?:\r?\n|\z)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * These KEP numbers are reserved for templates and testing.
         * They should be filtered out during processing.
         */
        private val TEMPLATE_KEP_NUMBERS = listOf("NNNN", "1234", "2345", "3456")

        private val EMPTY_META = KepYamlMeta(
            title = null,
            kepNumber = null,
            authors = emptyList(),
            owningSig = null,
            rawStatus = null,
            normalizedStatus = "unknown",
            implementedAtVersion = null,
            replaces = emptyList(),
            supersededBy = emptyList(),
            seeAlso = emptyList(),
        )
    }
}

/**
 * Strips a leading `NNNN-` (KEP number) or `YYYYMMDD-` (date) prefix from a path segment,
 * leaving the descriptive slug. Returns the input unchanged when no such prefix is present
 * (e.g., the legacy `k8s-image-promoter` form).
 */
private fun extractSlug(name: String): String {
    val m = Regex("""^\d+-(.+)$""").matchEntire(name) ?: return name
    return m.groupValues[1]
}

/** Reads a scalar value at [key] or null when missing / non-scalar / blank. */
private fun YamlMap.scalarOrNull(key: String): String? =
    (get<YamlNode>(key) as? YamlScalar)?.content?.takeIf { it.isNotBlank() }

/**
 * Reads [key] as a `YamlList` of scalars and returns their string contents. Returns an empty
 * list when the key is missing, the value is `null`, or the value isn't a list of scalars.
 */
private fun YamlMap.scalarListOrEmpty(key: String): List<String> {
    val list = get<YamlNode>(key) as? YamlList ?: return emptyList()
    return list.items.mapNotNull { (it as? YamlScalar)?.content?.takeIf { s -> s.isNotBlank() } }
}
