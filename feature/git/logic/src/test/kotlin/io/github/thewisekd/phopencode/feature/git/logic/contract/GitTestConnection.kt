package io.github.thewisekd.phopencode.feature.git.logic.contract

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

private val SSH_REF = SecretRef("git-ssh-login")
private val OPENCODE_REF = SecretRef("git-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

private val PROFILE = ServerProfile(
    id = ServerProfileId("git-profile"),
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A running `opencode serve` is always found, so a connect walks straight to Connected. */
private object RunningServer : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/**
 * A [ConnectionManager] on fakes, as the files feature's tests build it. Each connection's SSH
 * session is a [FakeSshSession], whose scripted command runner [lastSession] gives.
 */
internal class GitTestConnection(scope: CoroutineScope) {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(SERVER_KEY)

    val manager = ConnectionManager(
        connector = connector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = OpencodeClientFactory { _, _, _ -> FakeOpencodeClient() },
        scope = scope,
        options = ConnectionOptions(reconnect = ReconnectPolicy(delays = listOf(1.seconds), maxAttempts = 3)),
    )

    init {
        connector.addServer(SshTarget(HOST, PORT, USER))
    }

    /** Seeds the login and the host key, then starts the connect; the caller runs the scheduler. */
    suspend fun connect() {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        knownHosts.approve(HostEndpoint(HOST, PORT), HostKeyFingerprint(SERVER_KEY.fingerprintSha256))
        manager.connect(PROFILE)
    }

    fun lastSession(): FakeSshSession = connector.sessions.last()
}
