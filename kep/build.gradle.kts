plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(projects.utils)
    implementation(libs.kaml)
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
    args = listOf("full")
}

tasks.register<JavaExec>("runUpdate") {
    group = "scraping"
    description = "Run an incremental KEP scrape."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.kep.MainKt")
    args = listOf("update")
}
