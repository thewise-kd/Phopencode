package io.github.thewisekd.phopencode.core.ssh.api

import io.github.thewisekd.phopencode.core.model.ServerProfile
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.StateFlow

/**
 * Opens SSH connections. This package names no SSH library, so that JSch can replace sshj; the
 * implementation lives elsewhere in this module.
 */
interface SshConnector {
    /**
     * Connects and logs in. Returns only after [hostKeyVerifier] said [HostKeyDecision.Accept] and
     * login succeeded. No credential is sent before the host key is accepted. A verifier that
     * throws counts as a rejection.
     *
     * [credential] is not kept after this returns, so the caller can [wipe][SshCredential.wipe] it.
     * Cancelling closes the half-open connection.
     *
     * @throws SshException.HostKeyRejected
     * @throws SshException.AuthenticationFailed
     * @throws SshException.Timeout
     * @throws SshException.ConnectionFailed
     */
    suspend fun connect(
        target: SshTarget,
        credential: SshCredential,
        hostKeyVerifier: HostKeyVerifier,
        options: SshConnectOptions = SshConnectOptions(),
    ): SshSession
}

/** Where to connect. Prints nothing identifying, like [ServerProfile]. */
data class SshTarget(val host: String, val port: Int, val username: String) {
    init {
        require(host.isNotBlank()) { "Host is blank" }
        require(username.isNotBlank()) { "Username is blank" }
        require(port in PORTS) { "Port is out of range" }
    }

    override fun toString(): String = "SshTarget(***)"

    companion object {
        private val PORTS = 1..65535

        /** @throws IllegalArgumentException for a profile with invalid values; storage allows those. */
        fun from(profile: ServerProfile): SshTarget = SshTarget(profile.host, profile.port, profile.username)
    }
}

/**
 * @property connectTimeout Limits the network work of one [SshConnector.connect]. Time spent waiting
 *   for a user in [HostKeyVerifier.verify] does not count.
 * @property keepAliveInterval Time between keep-alive messages; null switches them off.
 * @property keepAliveMaxMissed Unanswered keep-alives after which the connection counts as lost.
 */
data class SshConnectOptions(
    val connectTimeout: Duration = 15.seconds,
    val keepAliveInterval: Duration? = 30.seconds,
    val keepAliveMaxMissed: Int = 3,
) {
    init {
        require(connectTimeout.isPositive() && connectTimeout.isFinite()) { "Connect timeout must be positive" }
        require(keepAliveInterval == null || (keepAliveInterval.isPositive() && keepAliveInterval.isFinite())) {
            "Keep-alive interval must be positive"
        }
        require(keepAliveMaxMissed > 0) { "Keep-alive limit must be positive" }
    }
}

/**
 * A resolved login secret. [io.github.thewisekd.phopencode.core.model.SshAuth] only points at
 * secrets; whoever reads them from `SecretStore` builds this for a single connect and then calls
 * [wipe]. These are character arrays and plain classes on purpose: a `String` cannot be wiped, and a
 * data class would add printing and `copy`.
 */
sealed class SshCredential {
    /** Overwrites the secrets in place. The arrays are the caller's own; nothing is copied. */
    abstract fun wipe()

    class Password(val password: CharArray) : SshCredential() {
        override fun wipe() = password.fill(ZERO)

        override fun toString(): String = "Password(***)"
    }

    /** @property privateKey The key file's text, for example OpenSSH format. */
    class PrivateKey(val privateKey: CharArray, val passphrase: CharArray? = null) : SshCredential() {
        override fun wipe() {
            privateKey.fill(ZERO)
            passphrase?.fill(ZERO)
        }

        override fun toString(): String = "PrivateKey(***)"
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}

/**
 * One logged-in connection. [close] is safe to call more than once and ends every process and
 * [SftpClient] of the session.
 */
interface SshSession : AutoCloseable {
    /** Turns false when the connection ends for any reason, including lost keep-alives. */
    val connected: StateFlow<Boolean>

    val exec: RemoteExec

    /**
     * Opens an SFTP channel confined to [root]; see [SftpClient] for what that means.
     *
     * @throws SshException.Sftp
     * @throws SshException.Disconnected
     */
    suspend fun openSftp(root: String): SftpClient

    /**
     * Gives sockets that reach the server's side of the network through this session, for an HTTP
     * client talking to a server that listens on the remote loopback. Each socket's `connect` opens
     * one `direct-tcpip` channel to the address it is given, as seen from the SSH server.
     *
     * No local port is listened on, so other apps on the phone cannot reach the tunnel. All sockets
     * of the factory close when the session closes, including ones that are still in use.
     */
    fun socketFactory(): SocketFactory

    override fun close()
}
