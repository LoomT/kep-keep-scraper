package dev.cse3000.loader

import java.nio.file.Path

/**
 * Maps `kep-*.jsonl` streams (under [normalizedDir]) to typed [Rows] for the
 * KEP project.
 */
class KepMapper(
    private val projectId: Int,
    private val normalizedDir: Path,
    private val personIds: IdAllocator,
    private val organisationIds: IdAllocator,
    private val commentIds: IdAllocator,
) {
    private val personByLogin = mutableMapOf<String, Long>()

    fun mapAll(): Rows {
        val rows = Rows(
            projects = listOf(
                Project(
                    projectId = projectId,
                    projectName = "Kubernetes",
                    enhancementProposalName = "KEP",
                    copyright = "Apache-2.0",
                ),
            ),
        )

        // TODO: read JSONL streams from `normalizedDir`, build SchemaModel rows, fold into `rows`.
        // See KeepMapper for the same pattern; the input streams differ by prefix.
        // Recommended grouping for KEP proposals: group `kep-yaml-revisions` rows by `_dir`
        // (the KEP directory keys/<sig>/<kep-name>/) — each unique dir is one Proposal.

        return rows
    }

    @Suppress("unused")
    private fun resolveLogin(login: String, fullName: String? = null): Long =
        personByLogin.getOrPut(login) { personIds.nextId() }
}
