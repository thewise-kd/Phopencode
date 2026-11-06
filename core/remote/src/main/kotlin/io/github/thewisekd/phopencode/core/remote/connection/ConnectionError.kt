package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import io.github.thewisekd.phopencode.core.remote.start.ServerStartException
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import java.io.IOException

/**
 * Why a connection failed or ended. None of these holds an address, a secret or server output,
 * except in the message of [Unexpected.cause], which nothing here reads or prints.
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

    /**
     * The profile names opencode [port], and an `opencode serve` of the login user runs, but none
     * listens on that loopback port. Nothing is started next to the running servers and none is
     * stopped; the user may fix the port, or a server may still be getting its socket.
     */
    data class ServerNotOnPort(val port: Int) : ConnectionError {
        override val retryable: Boolean get() = true
    }

    /** The SSH server refused the login. */
    data object AuthenticationFailed : ConnectionError

    /** The user did not approve [key]. */
    data class HostKeyRejected(val key: HostKey) : ConnectionError

    /**
     * The server presented [key], not the [approved] one. This may be an attack, so the connection
     * stops without asking; replacing the approved key is a separate, explicit user decision, made
     * with [ConnectionManager.acceptChangedHostKey] on the [ConnectionState.Error] that carries this.
     *
     * @property server The profile and the endpoint whose approved key differs. [ConnectionManager]
     *   always sets it; it is null only on a value made elsewhere, which no replace accepts.
     */
    data class HostKeyChanged(
        val key: HostKey,
        val approved: HostKeyFingerprint,
        val server: ConnectionServer? = null,
    ) : ConnectionError

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

    /**
     * A failure no other case describes, a stray `CancellationException` included: one that ended a
     * step while neither a stop nor the end of the manager's scope cancelled it (P9.24).
     *
     * Not [retryable]: nothing says such a failure passes with time. It is a bug or a library's
     * misbehaviour, not a network or server condition, and the same step would likely fail the
     * same way, so retrying would only loop through the backoff and hide it. The connection ends
     * in an error the user sees, with the cause's classes to name, and the manager stays usable:
     * the user's next connect starts afresh.
     *
     * @property cause The failure itself, when there was one, so that a screen can name its class
     *   chain (P9.22). Read its classes only: its message may quote an address or a server's reply.
     *   It takes no part in [equals], [hashCode] or [toString]: every `Unexpected` equals every
     *   other, as when this was an object, and printing one never prints the cause.
     */
    class Unexpected(val cause: Throwable? = null) : ConnectionError {
        override fun equals(other: Any?): Boolean = other is Unexpected

        override fun hashCode(): Int = Unexpected::class.java.name.hashCode()

        override fun toString(): String = "Unexpected"
    }
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
class ConnectionException(
    val error: ConnectionError,
    cause: Throwable? = null,
) : Exception("Connection failed: ${error::class.simpleName}", cause)

/**
 * No connection stands right now: [ConnectionManager] is idle, connecting, waiting to reconnect,
 * stopped by an error or closed, or the connection ended while the call was under way. Its message
 * is fixed text.
 */
class NotConnectedException : IOException("No live connection")
