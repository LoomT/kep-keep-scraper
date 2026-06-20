plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    api(libs.slf4jApi)
    implementation(libs.sqliteJdbc)
}

tasks.register<JavaExec>("combineProposals") {
    group = "utils"
    description = "Validate every sqlite db under data/shared/ (data.db + other_proposals/*) " +
            "against schema.sql and merge the valid ones into data/shared/all_proposals.db. " +
            "Skips files whose schema does not match (logged at ERROR)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.cse3000.utils.CombineProposalsKt")
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    listOf("sharedDir", "schemaPath", "monorepoRoot").forEach { name ->
        project.findProperty(name)?.let { systemProperty(name, it.toString()) }
    }
}
