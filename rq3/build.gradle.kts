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
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.cse3000.rq3.MainKt")
}

// Forward -PsharedDbPath / -PrunReport flags to the JVM.
tasks.named<JavaExec>("run").configure {
    listOf("sharedDbPath").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}

tasks.register("syncSharedDb") {
    group = "rq3"
    description = "Copy the shared proposals.db from -PsharedDbPath into rq3/data/shared/."
    doLast {
        val src = (project.findProperty("sharedDbPath") as? String)?.takeIf { it.isNotBlank() }
            ?: System.getenv("SHARED_DB_PATH")?.takeIf { it.isNotBlank() }
            ?: error(
                "Pass -PsharedDbPath=path/to/proposals.db (or set SHARED_DB_PATH env var). " +
                        "Typically your local checkout of the sibling proposals-db repo.",
            )
        val srcPath = Paths.get(src).toAbsolutePath()
        require(Files.exists(srcPath)) { "sharedDbPath does not exist: $srcPath" }
        val targetDir = layout.projectDirectory.dir("data/shared").asFile.toPath()
        Files.createDirectories(targetDir)
        val target = targetDir.resolve("proposals.db")
        Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Copied $srcPath -> $target")
    }
}
