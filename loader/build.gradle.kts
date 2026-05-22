plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(projects.scraper)
    implementation(libs.kaml)
    implementation(libs.sqliteJdbc)
}

application {
    mainClass.set("dev.cse3000.loader.MainKt")
}

// Forward selected -P<name>=<value> properties to the JVM as system properties so Main.kt can read them.
tasks.named<JavaExec>("run").configure {
    enableAssertions = true   // make `assert(...)` calls also work in non-debug mode.
    listOf(
        "keepProjectId",
        "kepProjectId",
        "dataDir",
        "out",
        "schemaPath",
        "monorepoRoot",
        "exportTypes"
    ).forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}
