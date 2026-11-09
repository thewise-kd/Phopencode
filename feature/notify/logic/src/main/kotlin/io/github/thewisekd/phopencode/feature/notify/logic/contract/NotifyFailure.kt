package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError

/**
 * Why a notify screen could not do what it was asked, as few cases as the screens word
 * differently. The pending list reads the server through [NotifyConnection], whose calls fail with
 * `ApiException` ([of] maps it); the setup works through [NotifyService], which fails with
 * [NotifyException], and through `SecretStore`.
 *
 * Carries no server text: [ApiError]'s `detail`, a remote command's output and an ntfy reply stay
 * out of the state.
 */
enum class NotifyFailure {
    /** No connection stands, or the one in use was closed. Going back to the connection fixes it. */
    NotConnected,

    /** The server could not be reached, or did not answer in time. Retrying may help. */
    Unreachable,

    /** The server refused the credentials. */
    Unauthorized,

    /** The item is gone: answered, cancelled or deleted meanwhile. */
    NotFound,

    /** The server refused the request. */
    Rejected,

    /** The server failed. */
    ServerFailed,

    /** The server has no `python3`, which the notification service needs; nothing was installed. */
    PythonMissing,

    /** The server's `python3` is older than the service needs (3.9); nothing was installed. */
    PythonTooOld,

    /** A step on the server failed: upload, writing the config, starting, stopping or removing. */
    InstallFailed,

    /** The topic or the ntfy server could not be read from or written to the phone's secret store. */
    StorageFailed,

    /** The ntfy server did not take the test notification. */
    NtfyFailed,

    Unknown,
    ;

    companion object {
        fun of(error: ApiError): NotifyFailure = when (error) {
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
 * What [NotifyService] throws. The message names the [failure] only, never a value from the server
 * or the phone, and no cause is chained, whose message could carry one; so it is safe to log.
 */
class NotifyException(val failure: NotifyFailure) : Exception("Notify failure: $failure")
