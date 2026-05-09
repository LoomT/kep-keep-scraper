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
