package dev.cse3000.loader

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
    }
}