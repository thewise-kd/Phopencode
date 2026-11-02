package io.github.thewisekd.phopencode.core.api

/** An opaque position in a list. Only the server gives these meaning. */
@JvmInline
value class PageCursor(val value: String)

/** One page of a list. Pass [nextCursor] back to get the next one; null means this is the last. */
data class Page<T>(
    val items: List<T>,
    val nextCursor: PageCursor? = null,
) {
    val hasMore: Boolean
        get() = nextCursor != null
}

enum class SortOrder {
    Ascending,
    Descending,
}
