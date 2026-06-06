plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(projects.scraper) // RepoMirror, JGit, kotlinx-serialization, coroutines
    implementation(libs.sqliteJdbc)
    implementation(libs.commons.csv)
}

application {
    mainClass.set("dev.cse3000.complexity.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
