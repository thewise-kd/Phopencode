package io.github.thewisekd.phopencode.feature.sessions.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError

/**
 * Why a sessions screen could not do what it was asked, as few cases as the screens word
 * differently. Every sessions screen talks to the server of the active connection: the client of
 * `ConnectionState.Connected` in `ConnectionManager.state`.
 *
 * Carries no server text: [ApiError]'s `detail` stays out of the state.
 */
enum class SessionsFailure {
    /** No connection stands, or the one in use was closed. Going back to the connection fixes it. */
    NotConnected,

    /** The server could not be reached, or did not answer in time. Retrying may help. */
    Unreachable,

    /** The server refused the credentials. */
    Unauthorized,

    /** The session is gone, for example deleted from another device. */
    NotFound,

    /** The session is busy or changed meanwhile. */
    Conflict,

    /** The server refused the request or the action. */
    Rejected,

    /** The server failed. */
    ServerFailed,

    Unknown,
    ;

    companion object {
        fun of(error: ApiError): SessionsFailure = when (error) {
            ApiError.Unauthorized -> Unauthorized
            ApiError.Forbidden, is ApiError.BadRequest -> Rejected
            ApiError.NotFound -> NotFound
            is ApiError.Conflict -> Conflict
            is ApiError.Server -> ServerFailed
            ApiError.Network, ApiError.Timeout -> Unreachable
            ApiError.Closed -> NotConnected
            is ApiError.VersionTooOld, ApiError.MalformedResponse, is ApiError.Unexpected -> Unknown
        }
    }
}
