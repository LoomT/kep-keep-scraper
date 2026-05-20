package dev.cse3000.loader

/**
 * Data classes mirroring `schema.sql`. Field order here matches the column
 * order in [SqlWriter] so adding a field is one of "two" places to update.
 */
data class Project(
    val projectId: Int,
    val projectName: String,
    val enhancementProposalName: String,
    val copyright: String,
)

data class Person(
    val personId: Int,
    val fullName: String?,
)

data class PersonIdentifier(
    val personId: Int,
    val domain: String,
    val identifierType: String,
    val identifier: String,
)

data class Organisation(
    val organisationId: Int,
    val organisationName: String,
)

data class Affiliation(
    val organisationId: Int,
    val personId: Int,
)

data class Proposal(
    val projectId: Int,
    val proposalId: String,
    val topic: String?,
    val proposalType: String? = null,
)

data class ProposalRevision(
    val projectId: Int,
    val proposalId: String,
    val revisionIndex: Int,
    val title: String,
    val createdAt: String,           // ISO-8601
    val content: String,
    val implementedAtVersion: String?,
)

data class ProposalRevisionAuthor(
    val projectId: Int,
    val proposalId: String,
    val revisionIndex: Int,
    val authorId: Int,
)

data class ProposalStatus(
    val projectId: Int,
    val proposalId: String,
    val statusIndex: Int,
    val rawStatus: String?,
    val normalisedStatus: String,
    val createdAt: String,           // ISO-8601
)

data class RelatedProposal(
    val projectId: Int,
    val proposalId: String,
    val relatedProjectId: Int,
    val relatedProposalId: String,
    val type: String,
)

data class Comment(
    val commentId: Int,
    val authorId: Int,
    val projectId: Int,
    val proposalId: String,
    val commentOnCommentId: Int?,
    val createdAt: String?,          // ISO-8601
    val content: String,
)

/** Aggregate of every table's rows produced by a Mapper. */
data class Rows(
    val projects: List<Project> = emptyList(),
    val persons: List<Person> = emptyList(),
    val personIdentifiers: List<PersonIdentifier> = emptyList(),
    val organisations: List<Organisation> = emptyList(),
    val affiliations: List<Affiliation> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val proposalRevisions: List<ProposalRevision> = emptyList(),
    val proposalRevisionAuthors: List<ProposalRevisionAuthor> = emptyList(),
    val proposalStatuses: List<ProposalStatus> = emptyList(),
    val relatedProposals: List<RelatedProposal> = emptyList(),
    val comments: List<Comment> = emptyList(),
) {
    operator fun plus(other: Rows): Rows = Rows(
        projects = projects + other.projects,
        persons = persons + other.persons,
        personIdentifiers = personIdentifiers + other.personIdentifiers,
        organisations = organisations + other.organisations,
        affiliations = affiliations + other.affiliations,
        proposals = proposals + other.proposals,
        proposalRevisions = proposalRevisions + other.proposalRevisions,
        proposalRevisionAuthors = proposalRevisionAuthors + other.proposalRevisionAuthors,
        proposalStatuses = proposalStatuses + other.proposalStatuses,
        relatedProposals = relatedProposals + other.relatedProposals,
        comments = comments + other.comments,
    )
}
