import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(projects.scraper)
    implementation(libs.sqliteJdbc)
    implementation(libs.dataframe)
    implementation(libs.kandyLetsPlot)
}

application {
    mainClass.set("dev.cse3000.analysis.MainKt")
    // Remove the warning that currently org.xerial:sqlite-jdbc library produces.
    // When bumping the version of that library, see if this jvm arg can be safely removed.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Forward -PsharedDbPath / -PrunReport flags to the JVM.
tasks.named<JavaExec>("run").configure {
    listOf("sharedDbPath", "monorepoRoot").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}

tasks.register("syncSharedDb") {
    group = "analysis"
    description = "Copy the shared proposals.db from -PsharedDbPath into data/shared/."
    val sharedDbPathProp = providers.gradleProperty("sharedDbPath")
    val sharedDbPathEnv = providers.environmentVariable("SHARED_DB_PATH")
    val targetDir = rootProject.layout.projectDirectory.dir("data/shared").asFile.toPath()

    doLast {
        val src = sharedDbPathProp.orNull?.takeIf { it.isNotBlank() }
            ?: sharedDbPathEnv.orNull?.takeIf { it.isNotBlank() }
            ?: error(
                "Pass -PsharedDbPath=path/to/proposals.db (or set SHARED_DB_PATH env var). " +
                        "Typically your local checkout of the sibling proposals-db repo.",
            )
        val srcPath = Paths.get(src).toAbsolutePath()
        require(Files.exists(srcPath)) { "sharedDbPath does not exist: $srcPath" }
        Files.createDirectories(targetDir)
        val target = targetDir.resolve("proposals.db")
        Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Copied $srcPath -> $target")
    }
}
