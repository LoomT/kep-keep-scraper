package dev.cse3000.kep

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class IsKepFilePathTest {

    @ParameterizedTest(name = "[{index}] accepts: {0}")
    @CsvSource(
        "keps/sig-architecture/0001-foo/kep.yaml",
        "keps/sig-release/0123-release-cadence/README.md",
        "keps/sig-storage/storage-class/0001-feature/README.md", // nested below sig
        "keps/sig-release/0123-release-cadence/readme.MD", // case-insensitive .md/.yaml
        "keps/sig-x/0001-y/kep.YAML",
    )
    fun accepts(path: String) {
        assertThat(isKepFilePath(path)).isTrue()
    }

    @ParameterizedTest(name = "[{index}] rejects: {0} ({1})")
    @CsvSource(
        "README.md, top-level README",
        "keps/README.md, top-level keps README (1 slash)",
        "keps/sig-release/README.md, sig dir README (2 slashes, redirect stub style)",
        "keps/sig-release/2019-04-15-foo.md, pre-migration single-file shape — never the modern README",
        "keps/prod-readiness/0123-release-cadence/kep.yaml, prod-readiness is a different gated area",
        "keps/NNNN-kep-template/README.md, template path",
        "keps/sig-x/0001-y/NNNN-kep-template-helper.md, template helper anywhere in the tree",
        "keps/sig-x/0001-y/OWNERS, OWNERS is not .md/.yaml",
        "keps/sig-x/0001-y/notes.txt, non .md / .yaml extension",
        "docs/README.md, outside keps/",
        "keps/sig-x/0001-y/some-other.md, .md but not README.md",
        "keps/sig-x/0001-y/some-other.yaml, .yaml but not kep.yaml",
    )
    fun rejects(path: String, why: String) {
        assertThat(isKepFilePath(path)).describedAs(why).isFalse()
    }
}
