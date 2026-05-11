plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(projects.scraper)
    implementation(libs.kaml)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.cse3000.loader.MainKt")
}

// Forward selected -P<name>=<value> properties to the JVM as system properties so Main.kt can read them.
tasks.named<JavaExec>("run").configure {
    enableAssertions = true   // make `assert(...)` calls also work in non-debug mode.
    listOf("keepProjectId", "kepProjectId", "dataDir", "out", "schemaPath", "monorepoRoot").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}
