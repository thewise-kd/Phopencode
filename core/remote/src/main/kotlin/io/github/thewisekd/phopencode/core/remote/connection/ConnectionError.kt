package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import io.github.thewisekd.phopencode.core.remote.start.ServerStartException
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint

/**
 * Why a connection failed or ended. None of these holds an address, a secret or server output.
 * [retryable] failures may pass with time, so a reconnect tries again after them; the others need
 * the user.
 */
sealed interface ConnectionError {
    val retryable: Boolean get() = false

    /** The profile holds values no connection can use, such as an empty host or a bad port. */
    data object InvalidProfile : ConnectionError

    /** A secret the profile points at is not in the store. */
    data object SecretMissing : ConnectionError

    /** A secret exists but can never be decrypted; the user must enter it again. */
    data object SecretUnreadable : ConnectionError

    /** Secret or known-host storage could not be reached. */
    data object StorageUnavailable : ConnectionError

    /** The SSH server could not be reached or the handshake failed. */
    data object Unreachable : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /** An SSH step ran out of time. */
    data object Timeout : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /** The SSH connection ended. */
    data object Disconnected : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /**
     * The SSH connection stood, but `opencode serve` stopped answering: the manager's event stream
     * dropped or ended. The server may have died or restarted.
     */
    data object ServerLost : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /**
     * An `opencode serve` runs and holds its loopback port, but the port does not answer. No other
     * server is started next to it and it is never stopped, so only waiting can help.
     */
    data object ServerUnresponsive : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /**
     * An `opencode serve` runs, but on no loopback port the app can reach: it listens only on
     * another address, or has no socket yet. No other server is started next to it and it is never
     * stopped; a server that is still starting may get its socket, so a reconnect tries again.
     */
    data object ServerUnusable : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /** The SSH server refused the login. */
    data object AuthenticationFailed : ConnectionError

    /** The user did not approve [key]. */
    data class HostKeyRejected(val key: HostKey) : ConnectionError

    /**
     * The server presented [key], not the [approved] one. This may be an attack, so the connection
     * stops without asking; replacing the approved key is a separate, explicit user decision.
     */
    data class HostKeyChanged(val key: HostKey, val approved: HostKeyFingerprint) : ConnectionError

    /** No `opencode serve` runs and no opencode binary was found. */
    data object OpencodeMissing : ConnectionError

    /**
     * An `opencode serve` runs, but neither the profile's credential nor a password from a start by
     * this app is known for it.
     */
    data object ServerPasswordUnknown : ConnectionError

    data class ServerDetectionFailed(val reason: DetectFailure) : ConnectionError {
        override val retryable: Boolean get() = reason == DetectFailure.TimedOut
    }

    data class ServerStartFailed(val reason: ServerStartFailure) : ConnectionError

    /** The server's API refused or failed, including [ApiError.VersionTooOld] and [ApiError.Unauthorized]. */
    data class Api(val error: ApiError) : ConnectionError {
        override val retryable: Boolean
            get() = error == ApiError.Network || error == ApiError.Timeout || error is ApiError.Server
    }

    /** A failure no other case describes. */
    data object Unexpected : ConnectionError
}

/** [ServerStartException] as a value. */
enum class ServerStartFailure {
    BinaryMissing,
    PortInUse,
    Exited,
    Timeout,
    UnexpectedOutput,
    StateNotWritten,
    ;

    companion object {
        fun of(exception: ServerStartException): ServerStartFailure = when (exception) {
            is ServerStartException.BinaryMissing -> BinaryMissing
            is ServerStartException.PortInUse -> PortInUse
            is ServerStartException.Exited -> Exited
            is ServerStartException.Timeout -> Timeout
            is ServerStartException.UnexpectedOutput -> UnexpectedOutput
            is ServerStartException.StateNotWritten -> StateNotWritten
        }
    }
}

/** Carries a [ConnectionError] out of a step. Its message is fixed text. */
class ConnectionException(val error: ConnectionError) : Exception("Connection failed: ${error::class.simpleName}")
