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
    mainClass.set("dev.cse3000.kep.MainKt")
}

tasks.register<JavaExec>("runFull") {
    group = "scraping"
    description = "Run a full KEP scrape."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.kep.MainKt")
    args = listOf("--mode=full")
}

tasks.register<JavaExec>("runUpdate") {
    group = "scraping"
    description = "Run an incremental KEP scrape."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.kep.MainKt")
    args = listOf("--mode=update")
}
