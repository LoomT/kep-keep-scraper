package dev.cse3000.loader

import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class KepMapperTest {

    @TempDir
    lateinit var dataDir: Path

    private val projectId = 2
    private val base = 2_000_000

    @Test
    fun `single KEP with yaml + readme in one commit yields one proposal and one revision`() {
        writeJsonl(
            "kep-proposal-revisions",
            yamlRow(
                dir = "keps/sig-network/0001-feature",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                yaml = kepYaml(
                    kepNumber = "1",
                    title = "Cool Feature",
                    sig = "sig-network",
                    status = "implementable",
                    authors = listOf("alice"),
                ),
            ),
            readmeRow(
                dir = "keps/sig-network/0001-feature",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                body = "# Cool Feature\n\nThe body.",
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals).singleElement().satisfies({ p ->
            assertThat(p.proposalId).isEqualTo("1")
            assertThat(p.topic).isEqualTo("network")
        })
        assertThat(rows.proposalRevisions).singleElement().satisfies({ rev ->
            assertThat(rev.title).isEqualTo("Cool Feature")
            assertThat(rev.content).contains("The body.")
        })
        assertThat(rows.proposalRevisionAuthors).singleElement().satisfies({ a ->
            assertThat(a.proposalId).isEqualTo("1")
            assertThat(a.revisionIndex).isEqualTo(0)
        })
        assertThat(rows.proposalStatuses).singleElement().satisfies({ s ->
            assertThat(s.rawStatus).isEqualTo("implementable")
            assertThat(s.normalisedStatus)
                .describedAs("KEP 'implementable' = approved but not yet shipped → 'review'")
                .isEqualTo("review")
        })
    }

    @Test
    fun `yaml carries forward when a later commit only touches the README`() {
        writeJsonl(
            "kep-proposal-revisions",
            yamlRow(
                dir = "keps/sig-storage/0002-storage-thing",
                sha = "sha-a",
                committedAt = "2026-01-01T00:00:00Z",
                yaml = kepYaml(
                    kepNumber = "2",
                    title = "Storage Thing",
                    sig = "sig-storage",
                    status = "provisional",
                    authors = listOf("bob"),
                ),
            ),
            readmeRow(
                dir = "keps/sig-storage/0002-storage-thing",
                sha = "sha-a",
                committedAt = "2026-01-01T00:00:00Z",
                body = "# Storage Thing\n\nbody v1",
            ),
            readmeRow(
                dir = "keps/sig-storage/0002-storage-thing",
                sha = "sha-b",
                committedAt = "2026-01-02T00:00:00Z",
                body = "# Storage Thing\n\nbody v2",
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposalRevisions).hasSize(2)
        assertThat(rows.proposalRevisions.map { it.content })
            .containsExactly("# Storage Thing\n\nbody v1", "# Storage Thing\n\nbody v2")
        // YAML carries forward into revision 1: same title (drawn from yaml) and one author row per revision.
        assertThat(rows.proposalRevisions.map { it.title }).containsOnly("Storage Thing")
        assertThat(rows.proposalRevisionAuthors).hasSize(2)
    }

    @Test
    fun `pre-migration single-file commit feeds into the same proposal as the modern README`() {
        writeJsonl(
            "kep-proposal-revisions",
            // C1: pre-migration single-file at keps/sig-x/2019-01-15-bar.md
            // (per-commit `path` is the historical name; head_dir tracks the modern dir).
            oldFormatRow(
                headDir = "keps/sig-x/0003-bar",
                historicalPath = "keps/sig-x/2019-01-15-bar.md",
                sha = "sha-pre",
                committedAt = "2026-01-01T00:00:00Z",
                content = "---\n" +
                        "title: Bar Proposal\n" +
                        "kep-number: \"3\"\n" +
                        "owning-sig: sig-x\n" +
                        "status: provisional\n" +
                        "authors:\n" +
                        "  - alice\n" +
                        "---\n" +
                        "early body",
            ),
            // C2: the migration commit — README.md at the modern path (rename target of the old .md).
            readmeRow(
                dir = "keps/sig-x/0003-bar",
                sha = "sha-mig",
                committedAt = "2026-01-02T00:00:00Z",
                body = "# Bar Proposal\n\nmigrated body",
                renamedFrom = "keps/sig-x/2019-01-15-bar.md",
            ),
            // The same commit emits the new kep.yaml file.
            yamlRow(
                dir = "keps/sig-x/0003-bar",
                sha = "sha-mig",
                committedAt = "2026-01-02T00:00:00Z",
                yaml = kepYaml(
                    kepNumber = "3",
                    title = "Bar Proposal",
                    sig = "sig-x",
                    status = "implementable",
                    authors = listOf("alice"),
                ),
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals)
            .describedAs("the pre-migration commit and the migration commit must collapse to one logical proposal")
            .singleElement()
            .satisfies({ assertThat(it.proposalId).isEqualTo("3") })

        assertThat(rows.proposalRevisions).hasSize(2)
        assertThat(rows.proposalRevisions[0].content)
            .describedAs("first revision body is from the pre-migration single-file front-matter split")
            .contains("early body")
        assertThat(rows.proposalRevisions[1].content).contains("migrated body")

        // Status transition: provisional (in old front-matter) -> implementable (in new yaml).
        assertThat(rows.proposalStatuses.map { it.rawStatus })
            .containsExactly("provisional", "implementable")
    }

    @Test
    fun `nested KEP under a sub-group still groups correctly and derives the sig topic`() {
        writeJsonl(
            "kep-proposal-revisions",
            yamlRow(
                dir = "keps/sig-storage/object-storage/0042-thing",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                yaml = kepYaml(
                    kepNumber = "42",
                    title = "Thing",
                    sig = "sig-storage",
                    status = "implementable",
                    authors = listOf("alice"),
                ),
            ),
            readmeRow(
                dir = "keps/sig-storage/object-storage/0042-thing",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                body = "# Thing\n\nBody",
            ),
        )

        val rows = runMapper()

        assertThat(rows.proposals).singleElement().satisfies({
            assertThat(it.proposalId).isEqualTo("42")
            assertThat(it.topic).isEqualTo("storage")
        })
    }

    @Test
    fun `commits without yaml number nor leading-number-in-dir are dropped`() {
        writeJsonl(
            "kep-proposal-revisions",
            // No yaml at all; the dir name has no leading number => proposalIdFromKey returns null.
            readmeRow(
                dir = "keps/sig-x/just-a-name",
                sha = "sha-1",
                committedAt = "2026-01-01T00:00:00Z",
                body = "# Mystery\n\nbody",
            ),
        )
        val rows = runMapper()
        assertThat(rows.proposals).isEmpty()
        assertThat(rows.proposalRevisions).isEmpty()
    }

    private fun runMapper(): Rows = KepMapper(
        projectId = projectId,
        normalizedDir = dataDir,
        personIds = IdAllocator(base),
        organisationIds = IdAllocator(base),
        commentIds = IdAllocator(base),
    ).mapAll()

    private fun writeJsonl(stream: String, vararg lines: String) {
        Files.writeString(dataDir.resolve("$stream.jsonl"), lines.joinToString("\n"))
    }

    private fun kepYaml(
        kepNumber: String,
        title: String,
        sig: String,
        status: String,
        authors: List<String>,
    ): String = buildString {
        append("title: ").append(title).append("\n")
        append("kep-number: \"").append(kepNumber).append("\"\n")
        append("owning-sig: ").append(sig).append("\n")
        append("status: ").append(status).append("\n")
        append("authors:\n")
        for (a in authors) append("  - ").append(a).append("\n")
    }

    private fun yamlRow(
        dir: String,
        sha: String,
        committedAt: String,
        yaml: String,
        renamedFrom: String? = null,
    ): String = revisionLine(
        headPath = "$dir/kep.yaml",
        path = "$dir/kep.yaml",
        sha = sha,
        committedAt = committedAt,
        contentText = yaml,
        renamedFrom = renamedFrom,
    )

    private fun readmeRow(
        dir: String,
        sha: String,
        committedAt: String,
        body: String,
        renamedFrom: String? = null,
    ): String = revisionLine(
        headPath = "$dir/README.md",
        path = "$dir/README.md",
        sha = sha,
        committedAt = committedAt,
        contentText = body,
        renamedFrom = renamedFrom,
    )

    private fun oldFormatRow(
        headDir: String,
        historicalPath: String,
        sha: String,
        committedAt: String,
        content: String,
    ): String = revisionLine(
        // head_path points to the modern README; the per-commit `path` is the
        // old single-file form (3 segments under keps/) — that's how the walker
        // surfaces pre-`git mv` commits after rename-following.
        headPath = "$headDir/README.md",
        path = historicalPath,
        sha = sha,
        committedAt = committedAt,
        contentText = content,
        renamedFrom = null,
    )

    private fun revisionLine(
        headPath: String,
        path: String,
        sha: String,
        committedAt: String,
        contentText: String,
        renamedFrom: String?,
        scrapedAt: String = "2026-06-01T00:00:00Z",
    ): String = buildString {
        append('{')
        append(""""head_path":"""").append(headPath).append('"')
        append(""","head_dir":"""").append(headPath.substringBeforeLast('/', "")).append('"')
        append(""","path":"""").append(path).append('"')
        append(""","commit_sha":"""").append(sha).append('"')
        append(""","committed_at":"""").append(committedAt).append('"')
        append(""","content_text":""")
        append(JsonPrimitive(contentText).toString())
        if (renamedFrom != null) {
            append(""","renamed_from":"""").append(renamedFrom).append('"')
        }
        append(""","_scraped_at":"""").append(scrapedAt).append('"')
        append('}')
    }
}
