package dev.cse3000.complexity.config

import java.time.LocalDate

/**
 * One entry from `repos-config.csv`.
 *
 * @param ownerRepo  GitHub `owner/repo` slug (e.g. `"python/cpython"`)
 * @param projectId  Unique identifier for the project, identical to project_id in the proposal dataset.
 * @param monthsAgo  How many months before [REFERENCE_DATE] the first proposal was introduced.
 *                   The oldest quarterly snapshot is placed just before this point.
 * @param subfolder  Optional subfolder to analyze in monorepos (e.g. `"clang"` for LLVM).
 */
data class ProjectConfig(
    val ownerRepo: String,
    val projectId: Int,
    val monthsAgo: Int,
    val subfolder: String? = null,
) {
    val owner: String get() = ownerRepo.substringBefore('/')
    val repo: String get() = ownerRepo.substringAfter('/')

    companion object {
        val REFERENCE_DATE: LocalDate = LocalDate.of(2026, 1, 1)

        fun loadAll(): List<ProjectConfig> {
            val text = ProjectConfig::class.java.getResourceAsStream("/repos-config.csv")
                ?.bufferedReader()?.readText()
                ?: error("repos-config.csv not found on classpath")
            return text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .map { line ->
                    val parts = line.split(',').map { it.trim() }
                    ProjectConfig(
                        ownerRepo = parts[0],
                        projectId = parts[1].toInt(),
                        monthsAgo = parts[2].toInt(),
                        subfolder = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                    )
                }
        }
    }
}

/**
 * Generates quarterly snapshot dates from [referenceDate] backwards, including one
 * quarter before the proposal-start date (i.e. [monthsAgo] months before [referenceDate]).
 * Dates are returned newest-first.
 */
fun quarterlySnapshots(
    referenceDate: LocalDate = ProjectConfig.REFERENCE_DATE,
    monthsAgo: Int,
): List<LocalDate> {
    val proposalStart = referenceDate.minusMonths(monthsAgo.toLong())
    val dates = mutableListOf<LocalDate>()
    var current = referenceDate
    while (current > proposalStart) {
        dates.add(current)
        current = current.minusMonths(3)
    }
    // Include one snapshot just before proposals started
    dates.add(current)
    return dates
}

/** Formats a date as a quarter label, e.g. `2025-Q4`. */
fun quarterLabel(date: LocalDate): String {
    val q = (date.monthValue - 1) / 3 + 1
    return "${date.year}-Q$q"
}
