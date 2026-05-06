plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    api(libs.bundles.kotlinxEcosystem)
    api(libs.bundles.ktorClient)
    api(libs.slf4jApi)
    api(libs.jgit)
    runtimeOnly(libs.logback)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.cse3000.gh.cli.MainKt")
}

tasks.register<JavaExec>("runUsers") {
    group = "scraping"
    description =
        "Fetch GitHub user metadata for a list of logins. Use -Pinput=path/to/logins.txt [-PdataDir=...] [-Plimit=N] [-Pmode=full|update]."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.gh.cli.UsersMainKt")
    listOf("input", "dataDir", "limit", "mode").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}
