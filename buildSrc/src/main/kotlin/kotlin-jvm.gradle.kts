// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin in JVM projects.
    kotlin("jvm")
}

kotlin {
    // Use a specific Java version to make it easier to work in different environments.
    jvmToolchain(25)
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}

// Run all `JavaExec`-based tasks (application plugin's `run`, plus our custom `runFull` / `runUpdate` /
// `runUsers`) with the repo root as their working directory. That way every scraper's relative
// `data/` path (Env.dataDir() and the rq3 sync target) resolves to a single shared `<root>/data/`
// instead of `<module>/data/`, and the loader can read everything from one place without a copy step.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}
