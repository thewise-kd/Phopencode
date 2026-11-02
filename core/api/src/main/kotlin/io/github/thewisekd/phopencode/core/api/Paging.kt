package io.github.thewisekd.phopencode.core.api

/** An opaque position in a list. Only the server gives these meaning. */
@JvmInline
value class PageCursor(val value: String)

/**
 * One page of a list, with the two parts of the server's `cursor` object. Pass [nextCursor] back to
 * get the next page.
 *
 * The server sends `next` on the last page too, so a cursor does not promise more items. Only an
 * empty page ends the list, or no cursor at all; see [isEnd].
 */
data class Page<T>(
    val items: List<T>,
    val previousCursor: PageCursor? = null,
    val nextCursor: PageCursor? = null,
) {
    /** True when nothing can follow. False only means that asking for the next page is worthwhile. */
    val isEnd: Boolean
        get() = items.isEmpty() || nextCursor == null
}

enum class SortOrder {
    Ascending,
    Descending,
}
