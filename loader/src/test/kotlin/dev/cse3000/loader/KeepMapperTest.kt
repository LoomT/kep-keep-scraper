package dev.cse3000.loader

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KeepMapperTest {

    @TempDir
    lateinit var dataDir: Path

    private val projectId = 1
    private val base = 1_000_000

    @Test
    fun `single proposal across two commits emits one Proposal and two ProposalRevisions`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0001-foo.md",
                path = "proposals/KEEP-0001-foo.md",
                sha = "sha-2",
                committedAt = "2026-01-02T00:00:00Z",
                content = proposalText("Foo Proposal", "Stable", author = "Alice Author"),
            ),
            revisionRow(
                headPath = "proposals/KEEP-0001-foo.md",
                path = "proposals/KEEP-0001-foo.md",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("Foo Proposal", "Submitted", author = "Alice Author"),
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals).singleElement().satisfies({ p ->
            assertThat(p.proposalId).isEqualTo("0001")
            assertThat(p.topic).isEqualTo("design") // taken from `Type:` meta in the proposal text
        })
        assertThat(rows.proposalRevisions).hasSize(2)
        assertThat(rows.proposalRevisions.map { it.revisionIndex }).containsExactly(0, 1)
        assertThat(rows.proposalRevisions.map { it.title }).containsOnly("Foo Proposal")
        assertThat(rows.proposalStatuses)
            .describedAs("status changed Submitted → Stable, so 2 status rows")
            .hasSize(2)
        assertThat(rows.proposalStatuses.map { it.normalisedStatus }).containsExactly("draft", "accepted")
    }

    @Test
    fun `rename-aware grouping keeps a renamed proposal as one logical proposal`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0001-bar.md",
                path = "proposals/KEEP-0001-foo.md",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("Foo", "Submitted", author = "Alice"),
            ),
            revisionRow(
                headPath = "proposals/KEEP-0001-bar.md",
                path = "proposals/KEEP-0001-bar.md",
                sha = "sha-2",
                committedAt = "2026-01-02T00:00:00Z",
                content = proposalText("Foo Renamed", "Stable", author = "Alice"),
                renamedFrom = "proposals/KEEP-0001-foo.md",
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals)
            .describedAs("rename must collapse into a single logical proposal")
            .singleElement()
            .satisfies({ assertThat(it.proposalId).isEqualTo("0001") })
        assertThat(rows.proposalRevisions).hasSize(2)
    }

    @Test
    fun `collision on bare id is suffixed deterministically by HEAD path`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0001-alpha.md",
                path = "proposals/KEEP-0001-alpha.md",
                sha = "sha-a",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("Alpha", "Submitted", author = "Alice"),
            ),
            revisionRow(
                headPath = "proposals/KEEP-0001-beta.md",
                path = "proposals/KEEP-0001-beta.md",
                sha = "sha-b",
                committedAt = "2026-01-02T00:00:00Z",
                content = proposalText("Beta", "Submitted", author = "Bob"),
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals.map { it.proposalId })
            .describedAs("two HEAD paths sharing bare id 0001 must produce distinct suffixed PKs")
            .containsExactlyInAnyOrder("0001-0", "0001-1")
        assertThat(rows.proposalRevisions.map { it.proposalId })
            .containsExactlyInAnyOrder("0001-0", "0001-1")
    }

    @Test
    fun `stdlib path falls back to the stdlib topic when no Type meta is present`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/stdlib/KEEP-0042-stdlibthing.md",
                path = "proposals/stdlib/KEEP-0042-stdlibthing.md",
                sha = "sha-a",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalTextWithoutType("Stdlib Thing", "Submitted", author = "Alice"),
            ),
        )
        val rows = runMapper()
        assertThat(rows.proposals.single().topic).isEqualTo("Standard Library API proposal")
    }

    @Test
    fun `non-stdlib path falls back to the Design topic when no Type meta is present`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0099-design.md",
                path = "proposals/KEEP-0099-design.md",
                sha = "sha-a",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalTextWithoutType("Design Thing", "Submitted", author = "Alice"),
            ),
        )
        val rows = runMapper()
        assertThat(rows.proposals.single().topic).isEqualTo("Design proposal")
    }

    @Test
    fun `TEMPLATE proposals are excluded from output`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/TEMPLATE.md",
                path = "proposals/TEMPLATE.md",
                sha = "sha-t",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("Template", "Submitted", author = "Alice"),
            ),
            revisionRow(
                headPath = "proposals/KEEP-0001-real.md",
                path = "proposals/KEEP-0001-real.md",
                sha = "sha-r",
                committedAt = "2026-01-02T00:00:00Z",
                content = proposalText("Real", "Submitted", author = "Alice"),
            ),
        )
        val rows = runMapper()
        assertThat(rows.proposals.map { it.proposalId }).containsExactly("0001")
    }

    @Test
    fun `commit-side login enrichment upgrades a name-only proposal author to a GH login`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0001-feature.md",
                path = "proposals/KEEP-0001-feature.md",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("Feature", "Submitted", author = "Alice Author"),
            ),
        )
        writeJsonl(
            "keep-commits",
            commitRow(sha = "sha-1", login = "alicelogin", gitName = "Alice Author", gitEmail = "alice@example.com"),
        )

        val rows = runMapper()

        // Exactly one Person with full_name "Alice Author".
        val alicePerson = rows.persons.single { it.fullName == "Alice Author" }
        // After the merge, the GitHub `alicelogin` identifier should point at that same
        // person id (name-only persons survive; the login person is collapsed into them).
        val ghIdentifierForAlice = rows.personIdentifiers.single {
            it.domain == "github.com" && it.identifierType == "username" && it.identifier == "alicelogin"
        }
        assertThat(ghIdentifierForAlice.personId).isEqualTo(alicePerson.personId)

        // Git author observation should also be attached.
        assertThat(rows.personIdentifiers).anySatisfy { id ->
            assertThat(id.domain).isEqualTo("git_author")
            assertThat(id.identifierType).isEqualTo("username")
            assertThat(id.identifier).isEqualTo("Alice Author")
            assertThat(id.personId).isEqualTo(alicePerson.personId)
        }
    }

    @Test
    fun `latest-scrape dedup picks the row with the newest _scraped_at`() {
        writeJsonl(
            "keep-proposal-revisions",
            revisionRow(
                headPath = "proposals/KEEP-0001-x.md",
                path = "proposals/KEEP-0001-x.md",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("OLD title", "Submitted", author = "Alice"),
                scrapedAt = "2025-12-31T00:00:00Z",
            ),
            revisionRow(
                headPath = "proposals/KEEP-0001-x.md",
                path = "proposals/KEEP-0001-x.md",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                content = proposalText("FRESH title", "Submitted", author = "Alice"),
                scrapedAt = "2026-06-01T00:00:00Z",
            ),
        )
        val rows = runMapper()
        assertThat(rows.proposalRevisions.single().title).isEqualTo("FRESH title")
    }

    private fun runMapper(): Rows = KeepMapper(
        projectId = projectId,
        normalizedDir = dataDir,
        personIds = IdAllocator(base),
        organisationIds = IdAllocator(base),
        commentIds = IdAllocator(base),
    ).mapAll()

    private fun writeJsonl(stream: String, vararg lines: String) {
        Files.writeString(dataDir.resolve("$stream.jsonl"), lines.joinToString("\n"))
    }

    private fun proposalText(title: String, status: String, author: String): String = """
        |# $title
        |
        |* **Type**: design
        |* **Author**: $author
        |* **Status**: $status
        |
        |## Summary
        |
        |Body content goes here.
    """.trimMargin()

    private fun proposalTextWithoutType(title: String, status: String, author: String): String = """
        |# $title
        |
        |* **Author**: $author
        |* **Status**: $status
        |
        |## Summary
        |
        |Body content goes here.
    """.trimMargin()

    private fun revisionRow(
        headPath: String,
        path: String,
        sha: String,
        committedAt: String,
        content: String,
        renamedFrom: String? = null,
        scrapedAt: String = "2026-06-01T00:00:00Z",
    ): String = buildString {
        append('{')
        append(""""head_path":"""").append(headPath).append('"')
        append(""","head_dir":"""").append(headPath.substringBeforeLast('/', "")).append('"')
        append(""","path":"""").append(path).append('"')
        append(""","commit_sha":"""").append(sha).append('"')
        append(""","committed_at":"""").append(committedAt).append('"')
        append(""","content_text":""")
        append(kotlinx.serialization.json.JsonPrimitive(content).toString())
        if (renamedFrom != null) {
            append(""","renamed_from":"""").append(renamedFrom).append('"')
        }
        append(""","_scraped_at":"""").append(scrapedAt).append('"')
        append('}')
    }

    private fun commitRow(
        sha: String,
        login: String,
        gitName: String,
        gitEmail: String,
        scrapedAt: String = "2026-06-01T00:00:00Z",
    ): String =
        """{"sha":"$sha","author":{"login":"$login"},"commit":{"author":{"name":"$gitName","email":"$gitEmail"}},"_scraped_at":"$scrapedAt"}"""
}
