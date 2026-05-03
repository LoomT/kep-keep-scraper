plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(projects.scraper)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.cse3000.keep.MainKt")
}

tasks.register<JavaExec>("runFull") {
    group = "scraping"
    description = "Run a full KEEP scrape."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.keep.MainKt")
    args = listOf("--mode=full")
}

tasks.register<JavaExec>("runUpdate") {
    group = "scraping"
    description = "Run an incremental KEEP scrape."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.keep.MainKt")
    args = listOf("--mode=update")
}
