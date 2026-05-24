package dev.cse3000.kep

internal fun isKepFile(path: String): Boolean {
    if (!path.startsWith("keps/")) return false
    if (path.startsWith("keps/prod-readiness")) return false
    if (path.contains("NNNN-kep-template")) return false
    // Modern KEPs live at `keps/<sig>/<dirs>/{kep.yaml,README.md}` (3+ slashes under `keps/`).
    if (path.count { it == '/' } < 3) return false
    return path.endsWith("README.md", ignoreCase = true) || path.endsWith("kep.yaml", ignoreCase = true)
}