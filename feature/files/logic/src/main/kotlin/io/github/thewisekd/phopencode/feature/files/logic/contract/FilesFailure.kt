package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException

/**
 * Why a files screen could not do what it was asked, as few cases as the screens word differently.
 * Every files screen works on the server of the active connection, through the ports in
 * `FilePorts.kt`, which fail with [FilesException].
 *
 * Carries no server text and no path.
 */
enum class FilesFailure {
    /** No connection stands, or the one in use was closed. Going back to the connection fixes it. */
    NotConnected,

    /** The server could not be reached, or did not answer in time. Retrying may help. */
    Unreachable,

    /** The server refused the login or its host key. */
    Unauthorized,

    /** The file or folder is gone, for example deleted from another device or by an agent. */
    NotFound,

    /** The server's user may not read or change it. */
    PermissionDenied,

    /** Something already has that name. */
    AlreadyExists,

    /** The folder still has content. */
    NotEmpty,

    /** The path leaves the project's folder or is malformed; nothing was sent. */
    PathRejected,

    /** The file is larger than the limit of the read. */
    TooLarge,

    /** The content search tool asked for, such as `rg`, is not installed on the server. */
    ToolMissing,

    /** The server refused the request. */
    Rejected,

    /** The server failed. */
    ServerFailed,

    /** Neither the server nor this build can do it, such as before the file service is wired. */
    Unsupported,

    Unknown,
    ;

    companion object {
        fun of(error: ApiError): FilesFailure = when (error) {
            ApiError.Unauthorized -> Unauthorized
            ApiError.Forbidden -> PermissionDenied
            is ApiError.BadRequest, is ApiError.Conflict -> Rejected
            ApiError.NotFound -> NotFound
            is ApiError.Server -> ServerFailed
            ApiError.Network, ApiError.Timeout -> Unreachable
            ApiError.Closed -> NotConnected
            is ApiError.VersionTooOld, ApiError.MalformedResponse, is ApiError.Unexpected -> Unknown
        }

        fun of(failure: SftpFailure): FilesFailure = when (failure) {
            SftpFailure.NotFound -> NotFound
            SftpFailure.PermissionDenied -> PermissionDenied
            SftpFailure.AlreadyExists -> AlreadyExists
            SftpFailure.NotEmpty -> NotEmpty
            SftpFailure.PathRejected -> PathRejected
            SftpFailure.TooLarge -> TooLarge
            SftpFailure.Failed -> ServerFailed
        }

        fun of(error: SshException): FilesFailure = when (error) {
            is SshException.ConnectionFailed, is SshException.Timeout -> Unreachable
            is SshException.Disconnected -> NotConnected
            is SshException.AuthenticationFailed, is SshException.HostKeyRejected -> Unauthorized
            is SshException.OutputLimitExceeded -> TooLarge
            is SshException.Sftp -> of(error.failure)
        }
    }
}

/**
 * How every port in `FilePorts.kt` fails. The message is fixed text naming [failure] only, so that
 * no path reaches a log through it. Cancellation is never wrapped: it stays a
 * `CancellationException`.
 *
 * @param cause The original error; it must not be one whose message quotes a path or a secret.
 */
class FilesException(val failure: FilesFailure, cause: Throwable? = null) :
    Exception("File operation failed: $failure", cause)
