package io.github.thewisekd.phopencode.feature.preview.logic.service

import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionOptions
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ReconnectPolicy
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope

private const val HOST = "server.example"
private const val PORT = 22
private const val USER = "tester"

private val SSH_REF = SecretRef("preview-ssh-login")
private val OPENCODE_REF = SecretRef("preview-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

/** The port the fixture's `opencode serve` listens on. */
internal const val OPENCODE_PORT = 4096

internal val PREVIEW_PROFILE = ServerProfile(
    id = ServerProfileId("preview-profile"),
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A running `opencode serve` on [host] (a loopback address) and [OPENCODE_PORT]. */
private class RunningServer(private val host: String) : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running(host, OPENCODE_PORT, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/**
 * A [ConnectionManager] on fakes; [sessions] are in connect order. The opencode server runs on
 * [serverHost], port [OPENCODE_PORT]. Reconnects after one second.
 */
internal class PreviewConnection(scope: CoroutineScope, serverHost: String = "127.0.0.1") {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(SERVER_KEY)

    /** When set, every preview tunnel open on a session throws it, before it reaches the fake session. */
    @Volatile
    var tunnelFailure: Exception? = null

    /** When set, runs after a tunnel opened on a session, before the manager hands it out. */
    @Volatile
    var afterTunnelOpen: (() -> Unit)? = null

    /** Lets the tests interfere with tunnel opens; everything else goes to the fake session. */
    private val hookedConnector = object : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            val inner = connector.connect(target, credential, hostKeyVerifier, options)
            return object : SshSession by inner {
                override fun openLoopbackTunnel(port: Int): SshSession.LoopbackTunnel {
                    tunnelFailure?.let { throw it }
                    return inner.openLoopbackTunnel(port).also { afterTunnelOpen?.invoke() }
                }
            }
        }
    }

    val manager = ConnectionManager(
        connector = hookedConnector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer(serverHost)),
        clients = OpencodeClientFactory { _, _, _ -> FakeOpencodeClient() },
        scope = scope,
        options = ConnectionOptions(reconnect = ReconnectPolicy(delays = listOf(1.seconds), maxAttempts = 3)),
    )

    val sessions: List<FakeSshSession>
        get() = connector.sessions

    init {
        connector.addServer(SshTarget(HOST, PORT, USER))
    }

    /** Seeds the login and the host key, then starts the connect; the caller runs the scheduler. */
    suspend fun connect() {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        val endpoint = HostEndpoint(HOST, PORT)
        if (knownHosts.approved(endpoint) == null) knownHosts.approve(endpoint, HostKeyFingerprint(SERVER_KEY.fingerprintSha256))
        manager.connect(PREVIEW_PROFILE)
    }
}
