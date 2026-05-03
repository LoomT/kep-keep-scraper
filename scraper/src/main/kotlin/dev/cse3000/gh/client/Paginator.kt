package dev.cse3000.gh.client

object Paginator {
    private val LINK_NEXT = Regex("""<([^>]+)>;\s*rel="next"""")
    private val LINK_LAST_PAGE = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="last"""")

    fun nextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        return LINK_NEXT.find(linkHeader)?.groupValues?.getOrNull(1)
    }

    fun lastPageNumber(linkHeader: String?): Int? {
        if (linkHeader.isNullOrBlank()) return null
        return LINK_LAST_PAGE.find(linkHeader)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
