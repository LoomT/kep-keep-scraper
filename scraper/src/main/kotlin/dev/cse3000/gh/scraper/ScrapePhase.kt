package dev.cse3000.gh.scraper

enum class ScrapePhase(val cli: String) {
    REPO_INFO("repo-info"),
    ISSUES("issues"),
    PRS("prs"),
    DISCUSSIONS("discussions"),
    PROPOSALS("proposals"),
    COMMITS("commits"),
    USERS("users");

    companion object {
        val all: Set<ScrapePhase> = entries.toSet()

        fun parse(token: String): ScrapePhase? =
            entries.firstOrNull { it.cli == token.trim().lowercase() }

        /**
         * Parse a comma-separated list of phase tokens. Throws on any unknown
         * token. Empty/blank input returns all phases.
         */
        fun parseList(csv: String?): Set<ScrapePhase> {
            if (csv.isNullOrBlank()) return all
            val tokens = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return all
            val unknown = tokens.filter { parse(it) == null }
            require(unknown.isEmpty()) {
                "Unknown --include tokens: $unknown (valid: ${entries.map { it.cli }})"
            }
            return tokens.mapNotNull(::parse).toSet()
        }
    }
}
