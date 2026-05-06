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