package dev.cse3000.gh.client

object Paginator {
    private val LINK_NEXT = Regex("""<([^>]+)>;\s*rel="next"""")

    fun nextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        return LINK_NEXT.find(linkHeader)?.groupValues?.getOrNull(1)
    }
}
