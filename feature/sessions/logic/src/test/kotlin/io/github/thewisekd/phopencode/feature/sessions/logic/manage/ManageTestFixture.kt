package io.github.thewisekd.phopencode.feature.sessions.logic.manage

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.Page
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.SessionParentFilter
import io.github.thewisekd.phopencode.core.api.SessionQuery
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
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
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

private const val HOST = "server.example"
private const val PORT = 22
private const val USER = "tester"

private val SSH_REF = SecretRef("manage-ssh-login")
private val OPENCODE_REF = SecretRef("manage-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

internal val PROFILE = ServerProfile(
    id = ServerProfileId("manage-profile"),
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A second profile on the same fake host: only the profile tells the two apart. */
internal val OTHER_PROFILE = PROFILE.copy(id = ServerProfileId("other-profile"), name = "Other server")

/** A running `opencode serve` is always found, so a connect walks straight to Connected. */
private object RunningServer : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/**
 * A [ConnectionManager] on fakes. Every connection gets a new [FakeOpencodeClient], kept in
 * [clients] in order, and [arrange] is run on it before the connection uses it, so each server
 * starts with the sessions a test wants.
 */
internal class ManageConnection(scope: CoroutineScope) {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(SERVER_KEY)

    val clients = mutableListOf<FakeOpencodeClient>()

    @Volatile
    var arrange: (FakeOpencodeClient) -> Unit = {}

    val manager = ConnectionManager(
        connector = connector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = OpencodeClientFactory { _, _, _ ->
            val client = FakeOpencodeClient()
            arrange(client)
            synchronized(clients) { clients += client }
            Steered(client)
        },
        scope = scope,
        options = ConnectionOptions(reconnect = ReconnectPolicy(delays = listOf(1.seconds), maxAttempts = 3)),
    )

    init {
        connector.addServer(SshTarget(HOST, PORT, USER))
    }

    /** Seeds the login and the host key, then starts the connect; the caller runs the scheduler. */
    suspend fun connect(profile: ServerProfile = PROFILE) {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        knownHosts.approve(HostEndpoint(HOST, PORT), HostKeyFingerprint(SERVER_KEY.fingerprintSha256))
        manager.connect(profile)
    }

    fun lastClient(): FakeOpencodeClient = synchronized(clients) { clients.last() }

    /** When set, every children list fails with it; the other calls pass. */
    @Volatile
    var failChildren: ApiError? = null

    /** Every list query sent, on any connection, oldest first. */
    val queries = mutableListOf<SessionQuery>()

    /** Runs once, after the next `get` has its answer and before it returns it. */
    @Volatile
    var afterGet: (suspend () -> Unit)? = null

    /** Runs once, inside the next children list, before the server is asked. */
    @Volatile
    var beforeChildren: (suspend () -> Unit)? = null

    /** Connects [profile] again and waits until that new connection stands. */
    suspend fun switchTo(profile: ServerProfile) {
        val old = (manager.state.value as? ConnectionState.Connected)?.generation ?: -1
        manager.connect(profile)
        manager.state.first { it is ConnectionState.Connected && it.generation > old }
    }

    private inner class Steered(private val inner: FakeOpencodeClient) : OpencodeClient by inner {
        override val sessions: SessionApi = object : SessionApi by inner.sessions {
            override suspend fun get(id: SessionId): Session {
                val session = inner.sessions.get(id)
                afterGet?.also { afterGet = null }?.invoke()
                return session
            }

            override suspend fun list(query: SessionQuery): Page<Session> {
                synchronized(queries) { queries += query }
                if (query.parent is SessionParentFilter.ChildrenOf) beforeChildren?.also { beforeChildren = null }?.invoke()
                val failure = failChildren
                if (failure != null && query.parent is SessionParentFilter.ChildrenOf) throw ApiException(failure)
                return inner.sessions.list(query)
            }
        }
    }
}
