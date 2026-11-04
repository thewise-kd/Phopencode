package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.BinarySource
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.detect.VersionCheck
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal const val HOST = "ssh.example"
internal const val SSH_PORT = 22
internal const val USER = "tester"

internal val SSH_SECRET_REF = SecretRef("ssh-login")
internal val OPENCODE_REF = SecretRef("opencode-login")

/** Test secrets are assembled at runtime, so that no literal looks like a real one. */
internal val SSH_PASSWORD = "test-" + "ssh-" + "login"
internal val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

internal val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
internal val OTHER_KEY = HostKey("ssh-ed25519", ByteArray(32) { (it + 1).toByte() })

internal fun profile(
    auth: SshAuth = SshAuth.Password(SSH_SECRET_REF),
    opencodeCredential: SecretRef? = OPENCODE_REF,
) = ServerProfile(
    id = ServerProfileId("profile-1"),
    name = "Test server",
    host = HOST,
    port = SSH_PORT,
    username = USER,
    auth = auth,
    opencodeCredential = opencodeCredential,
)

internal val TARGET = SshTarget(HOST, SSH_PORT, USER)

/** Plays server discovery and startup; each step can be held open with a gate. */
internal class FakeServerTools : ServerTools {
    @Volatile
    var server: ServerLookup = ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    @Volatile
    var binary: BinaryLookup = BinaryLookup.Found("/opt/opencode/bin/opencode", BinarySource.KnownLocation, VersionCheck.Unparseable)

    @Volatile
    var startResult: (ServerStartOptions) -> StartedServer = { StartedServer("http://127.0.0.1:4100", 4100, 200) }

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    val startedWith = mutableListOf<Pair<ServerStartOptions, String>>()
    var findServerCalls = 0

    override suspend fun findServer(session: SshSession): ServerLookup {
        findServerCalls++
        gate?.await()
        return server
    }

    override suspend fun findBinary(session: SshSession): BinaryLookup = binary

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer {
        startedWith += options to password.revealText()
        return startResult(options)
    }
}

/** Makes [FakeOpencodeClient]s and records what each was made with. */
internal class RecordingClientFactory : OpencodeClientFactory {
    class Made(val baseUrl: String, val password: String, val sockets: SocketFactory, val client: FakeOpencodeClient)

    @Volatile
    var version: String = "2.2.5"

    /** Failures for the next clients' first call, one list entry per client. */
    val failures = ArrayDeque<ApiError>()

    val made = mutableListOf<Made>()

    /** The server's password; a client with another one is refused. Null accepts any. */
    @Volatile
    var serverPassword: (() -> String?)? = null

    override fun create(baseUrl: String, password: SecretValue, sockets: SocketFactory): OpencodeClient {
        val client = FakeOpencodeClient(serverVersion = version)
        failures.removeFirstOrNull()?.let { client.control.failNext(it) }
        val expected = serverPassword?.invoke()
        if (expected != null && expected != password.revealText()) client.control.failNext(ApiError.Unauthorized)
        made += Made(baseUrl, password.revealText(), sockets, client)
        return client
    }
}

internal class Fixture(
    val scope: TestScope,
    acceptsCredential: (SshCredential) -> Boolean = { it is SshCredential.Password && String(it.password) == SSH_PASSWORD },
    options: ConnectionOptions = ConnectionOptions(
        reconnect = ReconnectPolicy(delays = listOf(1.seconds, 2.seconds, 4.seconds), maxAttempts = 3),
    ),
    wrapConnector: (SshConnector) -> SshConnector = { it },
    managerScope: CoroutineScope = scope.backgroundScope,
) {
    val secrets = FakeSecretStore()
    val knownHosts = FakeKnownHostsStore()
    val connector = FakeSshConnector(SERVER_KEY, accepts = { _, credential -> acceptsCredential(credential) })
    val tools = FakeServerTools()
    val clients = RecordingClientFactory()
    val manager = ConnectionManager(
        connector = wrapConnector(connector),
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, tools),
        clients = clients,
        scope = managerScope,
        options = options,
    )

    /** Every state the manager published, in order. */
    val states = mutableListOf<ConnectionState>()

    init {
        connector.addServer(TARGET)
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            manager.state.collect { states += it }
        }
    }

    suspend fun seedSecrets() {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_SECRET_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
    }

    suspend fun trustServerKey() {
        knownHosts.approve(
            HostEndpoint(HOST, SSH_PORT),
            HostKeyFingerprint(SERVER_KEY.fingerprintSha256),
        )
    }
}
