package io.github.thewisekd.phopencode.core.ssh.api

import java.io.IOException

/**
 * Everything that can go wrong in this package. Messages are fixed text, so that no password, key,
 * command or address can reach a log through them. Implementations may pass a library's exception
 * as `cause`, unless it could quote a secret.
 *
 * [Timeout] is a real failure, deliberately unlike the coroutine `TimeoutCancellationException`,
 * which callers would silently treat as cancellation.
 */
sealed class SshException(message: String, cause: Throwable?) : IOException(message, cause) {
    /** The server could not be reached or the handshake failed. */
    class ConnectionFailed(cause: Throwable? = null) : SshException("SSH connection failed", cause)

    /** An established connection ended. */
    class Disconnected(cause: Throwable? = null) : SshException("SSH connection lost", cause)

    /** The server refused the credential. */
    class AuthenticationFailed(cause: Throwable? = null) : SshException("SSH login refused", cause)

    /** @property hostKey The key the server presented, for an approval dialog. */
    class HostKeyRejected(val hostKey: HostKey, cause: Throwable? = null) :
        SshException("SSH host key rejected", cause)

    class Timeout(cause: Throwable? = null) : SshException("SSH operation timed out", cause)

    class OutputLimitExceeded(val limitBytes: Long, cause: Throwable? = null) :
        SshException("Command output exceeded the limit", cause)

    class Sftp(val failure: SftpFailure, cause: Throwable? = null) :
        SshException("SFTP operation failed: $failure", cause)
}

enum class SftpFailure {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    NotEmpty,

    /** The path escapes the client's root or is malformed. Nothing was sent to the server. */
    PathRejected,

    /** The file is larger than the caller's limit. */
    TooLarge,

    /** Anything the server does not report more precisely. */
    Failed,
}
