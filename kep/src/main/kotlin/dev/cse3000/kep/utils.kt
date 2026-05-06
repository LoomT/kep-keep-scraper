package dev.cse3000.kep

internal fun isKepFile(path: String): Boolean {
    if (!path.startsWith("keps/")) return false
    val segments = path.split('/')
    if (segments.size != 4) return false
    val filename = segments[3]
    return filename == "kep.yaml" || filename == "README.md"
}