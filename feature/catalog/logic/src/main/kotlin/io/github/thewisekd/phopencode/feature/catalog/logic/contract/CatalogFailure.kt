package io.github.thewisekd.phopencode.feature.catalog.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError

/**
 * Why a catalog screen could not show what it was asked, as few cases as the screens word
 * differently. Every catalog screen reads the server of the active connection through
 * [CatalogConnection], whose calls fail with `ApiException`.
 *
 * Carries no server text: [ApiError]'s `detail` stays out of the state.
 */
enum class CatalogFailure {
    /** No connection stands, or the one in use was closed. Going back to the connection fixes it. */
    NotConnected,

    /** The server could not be reached, or did not answer in time. Retrying may help. */
    Unreachable,

    /** The server refused the credentials. */
    Unauthorized,

    /**
     * The item is not there: the server said so, or the list read has no entry with the route's
     * name, for example after it was removed from the server's configuration.
     */
    NotFound,

    /** The server refused the request. */
    Rejected,

    /** The server failed. */
    ServerFailed,

    Unknown,
    ;

    companion object {
        fun of(error: ApiError): CatalogFailure = when (error) {
            ApiError.Unauthorized -> Unauthorized
            ApiError.Forbidden, is ApiError.BadRequest, is ApiError.Conflict -> Rejected
            ApiError.NotFound -> NotFound
            is ApiError.Server -> ServerFailed
            ApiError.Network, ApiError.Timeout -> Unreachable
            ApiError.Closed -> NotConnected
            is ApiError.VersionTooOld, ApiError.MalformedResponse, is ApiError.Unexpected -> Unknown
        }
    }
}

/**
 * One list a screen reads from the server, with how far it got.
 *
 * @property loading The list is being read; [items] may still show the previous one.
 * @property items What was read last, in the server's order unless the screen's contract says
 *   otherwise.
 * @property error The last read failed; [items] keeps what was read before it, if anything.
 */
data class CatalogList<T>(
    val loading: Boolean = true,
    val items: List<T> = emptyList(),
    val error: CatalogFailure? = null,
) {
    /** Read, without an error and without entries: the server has none. */
    val isEmpty: Boolean
        get() = !loading && error == null && items.isEmpty()

    /** Entries can hold a path, a description or an error from the server: counts only. */
    override fun toString(): String = "CatalogList(loading=$loading, items=${items.size}, error=$error)"
}
