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
 * Each KEP lives in `keps/<sig>/<number>-<slug>/` and has two co-located files
 * we care about: `kep.yaml` (structured metadata) and `README.md` (the proposal
 * body). A single git commit usually touches both; we group revisions by
 * directory, sort by commit time, and pair each commit's yaml with its readme
 * (carrying forward the prior side when a commit touches only one).
 *
 * Early KEPs used a flat `keps/<sig>/<date>-<slug>.md` format. When such a file
 * was later migrated to the modern split layout, we associate its history with
 * the new dir by matching `<sig>` + the slug fragment. For these early KEP `.md`
 * files, the yaml content was embedded in the `.md` file itself.
 *
 * `proposal_id` is the bare KEP number (e.g. `"2313"`). A handful of dirs
 * collide on this prefix (three `0000-*` and two `2133-*`); the first dir wins
 * and the rest are dropped with a WARN.
 */
class KepMapper(
    private val projectId: Int,
    private val normalizedDir: Path,
    private val personIds: IdAllocator,
    @Suppress("unused") private val organisationIds: IdAllocator,
    private val commentIds: IdAllocator,
) {
    private val personByGHLogin = mutableMapOf<String, Long>()
    private val personByEmail = mutableMapOf<String, Long>()

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

        val rawRelated = proposalGroups.flatMap { it.relatedProposals }
        val (relatedProposals, danglingRelatedCount) = CommonMapper.processRelatedProposals(rawRelated, proposalIds)

        // KEP proposal_ids are bare numbers (e.g. "9", "2451"). The corresponding GitHub
        // tracking issue, when present, has the same number. Ids that don't parse as
        // integers are skipped (none expected today, but cheap insurance).
        val proposalIdByInt: Map<Int, String> = proposals
            .mapNotNull { p -> p.proposalId.toIntOrNull()?.let { it to p.proposalId } }
            .toMap()

        val prToProposalMap = buildPrToProposalMap(proposalIdByInt)

        val issueComments = CommonMapper.mapIssueComments(
            normalizedDir = normalizedDir,
            issuesStream = "kep-issues",
            issueCommentsStream = "kep-issue-comments",
            projectId = projectId,
            commentIds = commentIds,
            issueNumberToProposalId = { n -> proposalIdByInt[n] },
            resolveGHLogin = ::resolveGHLogin,
        )
        val prComments = CommonMapper.mapPrComments(
            normalizedDir = normalizedDir,
            pullsStream = "kep-pulls",
            prIssueCommentsStream = "kep-pr-issuecomments",
            prReviewCommentsStream = "kep-pr-review-comments",
            projectId = projectId,
            commentIds = commentIds,
            prToProposalMap = prToProposalMap,
            resolveGHLogin = ::resolveGHLogin,
        )
        val comments = issueComments + prComments

        CommonMapper.populateUserEmails(
            normalizedDir = normalizedDir,
            usersStream = "kep-users",
            personByGHLogin = personByGHLogin,
            personByEmail = personByEmail,
        )
        val (gitNameByEmail, gitFullNameByLogin) = CommonMapper.populateCommitterAuthorEmails(
            normalizedDir = normalizedDir,
            commitStreams = listOf("kep-commits", "kep-pr-commits"),
            personByGHLogin = personByGHLogin,
            personByEmail = personByEmail,
            resolveGHLogin = ::resolveGHLogin,
        )
        val ghLoginsToNames = CommonMapper.ghLoginsToNames(
            normalizedDir = normalizedDir,
            usersStream = "kep-users",
            personByGHLogin = personByGHLogin,
        )

        val persons = mutableListOf<Person>()
        val personUsernames = mutableListOf<PersonUsername>()
        val emittedPersonIds = mutableSetOf<Long>()
        fun emitPerson(id: Long, fullName: String?) {
            if (emittedPersonIds.add(id)) persons += Person(personId = id, fullName = fullName)
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
            emitPerson(id, null)
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
                    projectName = "Kubernetes",
                    enhancementProposalName = "KEP",
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

        log.info(
            "KEP resolver counts: proposals={}, revisions={}, related={}, dangling-related-dropped={}, " +
                    "persons={} (GH logins={}, emails={}), comments={}",
            rows.proposals.size, rows.proposalRevisions.size, rows.relatedProposals.size, danglingRelatedCount,
            rows.persons.size, personByGHLogin.size, personByEmail.size, rows.comments.size,
        )
        log.info(
            "KEP status mapping: rawStatus -> normalizedStatus distinct pairs = {}",
            rows.stageHistory.map { it.rawStatus to it.normalizedStatus }.distinct(),
        )

        return rows
    }

    private fun mapProposals(): List<ProposalGroup> {
        val yamlByKey = readJsonlObjects(normalizedDir, "kep-yaml-revisions")
            .keepLatestScrapesBy { it.getJsonString("path") + ":" + it.getJsonString("commit_sha") }
            .toList()
        val readmeByKey = readJsonlObjects(normalizedDir, "kep-readme-revisions")
            .keepLatestScrapesBy { it.getJsonString("path") + ":" + it.getJsonString("commit_sha") }
            .toList()

        // Pre-migration single-file KEPs: `keps/<sig>/<dateOrSlug>-*.md` — exactly 3 path
        // segments, ending in .md. PRR yaml files (`keps/prod-readiness/...`), example assets
        // inside modern dirs, and other deep paths are ignored — they're not predecessors of
        // a single modern KEP dir.
        val oldFormatByPath = readJsonlObjects(normalizedDir, "kep-revisions-other")
            .keepLatestScrapesBy { it.getJsonString("path") + ":" + it.getJsonString("commit_sha") }
            .filter {
                val p = it.getJsonString("path")
                p.endsWith(".md", ignoreCase = true) && p.count { c -> c == '/' } == 2 && p.startsWith("keps/")
            }
            .groupBy { it.getJsonString("path") }
            .toList()

        val byDir: Map<String, List<RawCommit>> = (yamlByKey + readmeByKey)
            .groupBy { it.getJsonString("dir") }
            .mapValues { (dir, jsons) ->
                val sigDir = dir.substringBeforeLast('/')                   // keps/sig-release
                val dirSlug = extractSlug(dir.substringAfterLast('/'))      // artifact-management

                val oldPaths = oldFormatByPath.filter { (oldPath, _) ->
                    oldPath.startsWith("$sigDir/") &&
                            extractSlug(
                                oldPath.substringAfterLast('/').removeSuffix(".md").removeSuffix(".MD")
                            ) == dirSlug
                }
                if (oldPaths.size > 1) {
                    log.warn(
                        "{}: {} candidate old-format paths matched same slug, picking first: {}",
                        dir, oldPaths.size, oldPaths.map { it.first },
                    )
                }
                if (oldPaths.isNotEmpty()) {
                    log.info("Found old format for {}: {}", dir, oldPaths.first().first)
                }

                val oldRawCommits = oldPaths.firstOrNull()?.second.orEmpty().map { json ->
                    RawCommit(
                        commitSha = json.getJsonString("commit_sha"),
                        committedAt = json.getJsonString("committed_at"),
                        yaml = null,
                        readme = null,
                        oldFormat = json,
                    )
                }

                val newRawCommits = jsons.groupBy { it.getJsonString("commit_sha") }
                    .map { (sha, group) ->
                        val yaml = group.find { it.getJsonString("path").endsWith("kep.yaml", ignoreCase = true) }
                        val readme = group.find { it.getJsonString("path").endsWith("README.md", ignoreCase = true) }
                        RawCommit(
                            commitSha = sha,
                            committedAt = group.first().getJsonString("committed_at"),
                            yaml = yaml,
                            readme = readme,
                            oldFormat = null,
                        )
                    }

                (oldRawCommits + newRawCommits).sortedBy { it.committedAt }
            }

        // Pass 1: claim a proposalId per dir, building proposalIdBySlug for later ref lookup.
        // First-come wins on number collisions; the loser is dropped here.
        val dirToProposalId = mutableMapOf<String, String>()
        val claimedProposalIds = mutableSetOf<String>()
        for ((dir, rawCommits) in byDir) {
            val lastCommitWithYaml = rawCommits.lastOrNull { it.yaml != null }
            val meta = lastCommitWithYaml?.yaml?.let { parseYamlMeta(it, dir, lastCommitWithYaml.commitSha) }
            val id = meta?.kepNumber ?: dir.proposalIdFromDir() ?: continue
            if (!claimedProposalIds.add(id)) {
                log.warn("Dropping {}: bare-number proposal_id '{}' already claimed", dir, id)
                continue
            }
            dirToProposalId[dir] = id
            val slug = extractSlug(dir.substringAfterLast('/'))
            proposalIdBySlug[slug] = id
        }

        // Pass 2: build the ProposalGroups using the now-populated proposalIdBySlug.
        return byDir.mapNotNull { (dir, rawCommits) ->
            val proposalId = dirToProposalId[dir] ?: return@mapNotNull null
            buildProposalGroup(dir, proposalId, rawCommits)
        }
    }

    /**
     * Folds a dir's raw commit list into [ProposalRevision]s. Revisions start at the first
     * commit that has a body (readme OR old-format `.md`); pre-migration commits emit
     * body-only revisions (no yaml meta). Once yaml appears it's carried forward; readme
     * likewise. Old-format and readme bodies aren't carried into each other — the migration
     * commit usually adds the readme in the same commit, so the discontinuity is one
     * revision at most.
     */
    private fun buildProposalGroup(dir: String, proposalId: String, rawCommits: List<RawCommit>): ProposalGroup? {
        val extracted = rawCommits.map { it to extractYamlAndBody(it) }
        val firstBodyIndex = extracted.indexOfFirst { it.second.second != null }
        if (firstBodyIndex < 0) {
            log.error("Skipping {}: no commit had body content (readme/old-format)", dir)
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

        for ((index, fc) in folded.withIndex()) {
            val meta =
                fc.yamlContent?.let { parseYamlMetaFromContent(it, "$dir@${fc.commitSha.take(8)}") } ?: EMPTY_META
            metas += meta
            revisions += ProposalRevision(
                projectId = projectId,
                proposalId = proposalId,
                revisionIndex = index,
                title = meta.title ?: dir.substringAfterLast('/'),
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
        // prefix); fall back to the dir's penultimate segment, which always exists.
        val topic = metas.firstNotNullOfOrNull { it.owningSig }?.removePrefix("sig-")
            ?: dir.substringBeforeLast('/').substringAfterLast('/').removePrefix("sig-")

        val proposal = Proposal(
            projectId = projectId,
            proposalId = proposalId,
            topic = topic,
        )

        // Emit a StageHistory row whenever the raw status changes between consecutive
        // revisions (matching the KEEP semantics).
        val stages = folded.zip(metas)
            .foldIndexed(mutableListOf<StageHistory>()) { idx, acc, (fc, meta) ->
                if (meta.rawStatus != null && (acc.isEmpty() || acc.last().rawStatus != meta.rawStatus)) {
                    acc += StageHistory(
                        projectId = projectId,
                        proposalId = proposalId,
                        stageIndex = idx,
                        normalizedStatus = meta.normalizedStatus,
                        rawStatus = meta.rawStatus,
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
            stages = stages,
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
            .onFailure { log.warn("yaml parse failed for {}: {}", label, it.message) }
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
     * Maps a parsed KEP status token onto one of the `StageHistory.normalized_status`
     * CHECK enum values: `accepted`, `rejected`, `draft`, `review`, `withdrawn`, `unknown`.
     *
     * KEP's canonical states are `provisional`, `implementable`, `implemented`, `deferred`,
     * `rejected`, `withdrawn`, `replaced` — we additionally see typos (`imlpemented`,
     * `implementeable`), the literal template string `provisional|implementable|…`, and a
     * few one-offs (`alpha`, `removed`, `superseded`).
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
            k.startsWith("superseded") -> "rejected"

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

            else -> {
                log.warn("MISSING_STATUS_MAPPING: '{}' (raw token); mapping to 'unknown'.", token)
                "unknown"
            }
        }
    }

    private fun resolveGHLogin(login: String): Long =
        personByGHLogin.getOrPut(login) { personIds.nextId() }

    /**
     * Scans `kep-pulls.jsonl` and links each PR to a proposal when the PR title references
     * a known KEP number (e.g. "KEP-1234", "kep 2345", "KEP1234"). Only the first match per
     * title is used; PRs whose referenced KEP isn't in our proposal set are skipped.
     *
     * The duplicate prevention is "first match per PR number wins" — `kep-pulls.jsonl` is
     * pre-deduped to one row per PR via [keepLatestScrapesBy], so collisions are between
     * different PRs pointing at the same proposal, which is fine: many-PRs → one-proposal
     * is the expected fan-in.
     */
    private fun buildPrToProposalMap(proposalIdByInt: Map<Int, String>): Map<Int, String> {
        var titled = 0
        val map = mutableMapOf<Int, MutableSet<String>>()
        readJsonlObjects(normalizedDir, "kep-pulls")
            .keepLatestScrapesBy { it["id"]!!.jsonPrimitive.long }
            .forEach { pull ->
                val title = pull.getJsonStringOrNull("title") ?: return@forEach
                val matches = KEP_IN_TITLE_REGEX.findAll(title)
                if (matches.none()) return@forEach
                titled++
                val kepNumbers = matches.map { it.groupValues[1].toIntOrNull() }
                val proposalIds = kepNumbers.mapNotNull { proposalIdByInt[it] }
                if (proposalIds.none()) return@forEach
                val prNumber = pull["number"]!!.jsonPrimitive.int
                map.getOrPut(prNumber) { mutableSetOf() }.addAll(proposalIds)
            }

        val (validMap, invalidMap) = map.entries.partition { it.value.size == 1 }

        log.info(
            "KEP PR→proposal title match: {} PRs mentioned a KEP number; " +
                    "{} resolved to a known single proposal; {} resolved to multiple proposals (ignoring).",
            titled, validMap.size, invalidMap.size,
        )
        return validMap.associate { it.key to it.value.single() }
    }

    /**
     * Pulls a bare proposal_id (e.g. `"2451"`) out of a `replaces` / `superseded-by` /
     * `see-also` reference. KEP yaml authors use these fields very loosely — values range
     * from clean repo-relative paths to external URLs to literal `"TBD"`.
     *
     * Resolution order:
     * 1. Modern path `/keps/<sig>/NNNN-<slug>` → `"NNNN"`. (null if NNNN is a template number)
     * 2. Old-format path `/keps/<sig>/<date>-<slug>.md` or `/keps/<sig>/<slug>.md` → look up
     *    the slug in [proposalIdBySlug] (built from the modern dir set).
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
        // Try modern form first.
        val leadingNumber = LEADING_KEP_NUMBER_REGEX.find(segment)?.groupValues?.get(1)
        if (leadingNumber != null && leadingNumber.length <= 5) {
            return if (leadingNumber !in TEMPLATE_KEP_NUMBERS) leadingNumber else null
        }
        // Old-format fallback: slug-match against our known proposals.
        val slug = extractSlug(segment)
        return proposalIdBySlug[slug]
    }

    private fun String.proposalIdFromDir(): String? {
        val last = substringAfterLast('/', "")
        val match = LEADING_KEP_NUMBER_REGEX.find(last) ?: return null
        return match.groupValues[1]
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
        val stages: List<StageHistory>,
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
