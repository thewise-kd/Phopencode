package io.github.thewisekd.phopencode.feature.sessions.logic.list

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
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val HOST = "server.example"
private const val PORT = 22
private const val USER = "tester"

private val SSH_REF = SecretRef("sessions-ssh-login")
private val OPENCODE_REF = SecretRef("sessions-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

internal val PROFILE = ServerProfile(
    id = ServerProfileId("sessions-profile"),
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A second profile on the same fake server: only the profile tells the two apart. */
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
 * Fails the list's own event streams, and not the manager's watch, which asks for other types: the
 * fake's own `fail` ends both, and the manager's reaction would hide the list's.
 */
internal class ListStreamBreaker {
    private val failures = MutableSharedFlow<ApiError>(extraBufferCapacity = 1)

    /** Fails the list streams open now; with none open, nothing happens. */
    fun fail(error: ApiError) {
        failures.tryEmit(error)
    }

    fun wrap(events: EventApi): EventApi = object : EventApi {
        override fun events(types: Set<String>): Flow<ServerEvent> {
            val inner = events.events(types)
            if (LIST_TYPE !in types) return inner
            return channelFlow {
                launch { inner.collect { send(it) } }
                throw ApiException(failures.first())
            }
        }
    }

    private companion object {
        const val LIST_TYPE = "session.created"
    }
}

/**
 * A [ConnectionManager] on fakes. Each connection takes [server] as its client: replace it before a
 * reconnect, since the manager closes the old one.
 */
internal class SessionsConnection(scope: CoroutineScope) {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(SERVER_KEY)

    @Volatile
    var server: FakeOpencodeClient = FakeOpencodeClient()

    val breaker = ListStreamBreaker()

    val manager = ConnectionManager(
        connector = connector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = OpencodeClientFactory { _, _, _ ->
            val fake = server
            object : OpencodeClient by fake {
                override val events: EventApi = breaker.wrap(fake.events)
            }
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
}
