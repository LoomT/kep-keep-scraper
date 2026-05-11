package dev.cse3000.kep

internal fun isKepFile(path: String): Boolean {
    if (!path.startsWith("keps/")) return false
    val rest = path.removePrefix("keps/")
    if (rest.startsWith("NNNN-kep-template") || rest.startsWith("prod-readiness") || rest.startsWith("README.md")) return false
    if (rest.endsWith("OWNERS")) return false
    return rest.endsWith(".md") || rest.endsWith(".yaml")
}