package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeClientFactory
import io.github.thewisekd.phopencode.core.remote.connection.ServerResolver
import io.github.thewisekd.phopencode.core.remote.connection.ServerTools
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.BinarySource
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.detect.VersionCheck
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import java.io.IOException
import javax.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal const val HOST = "ssh.example"
internal const val USER = "tester"
internal val SERVER_ID = ServerProfileId("server-1")
internal val SSH_REF = SecretRef("ssh-login")
internal val OPENCODE_REF = SecretRef("opencode-login")

/** Test secrets are assembled at runtime, so that no literal looks like a real one. */
internal val SSH_PASSWORD = "test-" + "ssh-" + "login"
internal val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"
internal val OTHER_PASSWORD = "test-" + "other-" + "login"

internal val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
internal val OTHER_KEY = HostKey("ssh-ed25519", ByteArray(32) { (it + 1).toByte() })

internal fun profile(
    id: ServerProfileId = SERVER_ID,
    opencodeCredential: SecretRef? = OPENCODE_REF,
) = ServerProfile(
    id = id,
    name = "Test server",
    host = HOST,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = opencodeCredential,
)

/** Plays server discovery and startup, and records every start. */
internal class FakeServerTools : ServerTools {
    @Volatile
    var server: ServerLookup = ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    @Volatile
    var binary: BinaryLookup =
        BinaryLookup.Found("/opt/opencode/bin/opencode", BinarySource.KnownLocation, VersionCheck.Unparseable)

    val starts = mutableListOf<ServerStartOptions>()

    override suspend fun findServer(session: SshSession): ServerLookup = server

    override suspend fun findBinary(session: SshSession): BinaryLookup = binary

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer {
        starts += options
        return StartedServer("http://127.0.0.1:4100", 4100, 200)
    }
}

/** Makes [FakeOpencodeClient]s; a client with a password other than [serverPassword] is refused. */
internal class FakeClients : OpencodeClientFactory {
    @Volatile
    var serverPassword: String? = null

    val passwords = mutableListOf<String>()

    override fun create(baseUrl: String, password: SecretValue, sockets: SocketFactory): OpencodeClient {
        val client = FakeOpencodeClient()
        val given = password.revealText()
        passwords += given
        val expected = serverPassword
        if (expected != null && expected != given) client.control.failNext(ApiError.Unauthorized)
        return client
    }
}

/** [FakeProfileRepository] whose saves can fail on their own, while reads still work. */
internal class SaveFailingProfiles(private val inner: FakeProfileRepository) : ProfileRepository by inner {
    @Volatile
    var failSaves: Boolean = false

    override suspend fun save(profile: ServerProfile) {
        if (failSaves) throw IOException("Fake save failure")
        inner.save(profile)
    }
}

/** Keeps a reference to every value [put] receives, so a test can see whether its owner wiped it. */
internal class CapturingSecrets(private val inner: SecretStore) : SecretStore by inner {
    val received = mutableListOf<SecretValue>()

    override suspend fun put(ref: SecretRef, value: SecretValue) {
        received += value
        inner.put(ref, value)
    }
}

/** Reads that wait for [gate], so a test decides when a presenter gets to connect. */
internal class GatedProfiles(private val inner: ProfileRepository) : ProfileRepository by inner {
    val gate = CompletableDeferred<Unit>()

    override suspend fun list(): List<ServerProfile> {
        gate.await()
        return inner.list()
    }
}

/** A real [ConnectionManager] over fakes, and the stores the presenter uses. */
internal class ProgressFixture(val test: TestScope) {
    val secrets = FakeSecretStore()
    val profiles = FakeProfileRepository()
    val profileStore = SaveFailingProfiles(profiles)
    val knownHosts = FakeKnownHostsStore()
    val connector = FakeSshConnector(SERVER_KEY, accepts = { _, credential ->
        credential is SshCredential.Password && String(credential.password) == SSH_PASSWORD
    })
    val tools = FakeServerTools()
    val clients = FakeClients()
    val activeServer = ActiveServer()
    val manager = ConnectionManager(
        connector = connector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, tools),
        clients = clients,
        scope = test.backgroundScope,
    )

    private var ids = 0

    init {
        connector.addServer(SshTarget(HOST, ServerProfile.DEFAULT_SSH_PORT, USER))
    }

    /** A scope of its own, so that a test can end it like a screen going away. */
    fun screenScope(): CoroutineScope {
        val parent = test.backgroundScope.coroutineContext
        return CoroutineScope(parent + Job(parent[Job]))
    }

    fun presenter(
        scope: CoroutineScope = screenScope(),
        serverId: ServerProfileId = SERVER_ID,
        profiles: ProfileRepository = profileStore,
        secretStore: SecretStore = secrets,
    ) = DefaultProgressPresenter(
        serverId = serverId,
        connection = manager,
        profiles = profiles,
        secrets = secretStore,
        activeServer = activeServer,
        scope = scope,
        newId = { "new-ref-${++ids}" },
    )

    /** Every event [presenter] sends, collected as it comes. */
    fun events(presenter: DefaultProgressPresenter): List<ProgressEvent> {
        val seen = mutableListOf<ProgressEvent>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            presenter.events.collect { seen += it }
        }
        return seen
    }

    suspend fun seed(profile: ServerProfile = profile(), opencodePassword: String? = OPENCODE_PASSWORD) {
        profiles.save(profile)
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        val ref = profile.opencodeCredential
        if (ref != null && opencodePassword != null) SecretValue.ofText(opencodePassword).use { secrets.put(ref, it) }
    }

    suspend fun trust(key: HostKey = SERVER_KEY) {
        knownHosts.approve(
            HostEndpoint(HOST, ServerProfile.DEFAULT_SSH_PORT),
            HostKeyFingerprint(key.fingerprintSha256),
        )
    }

    suspend fun storedText(ref: SecretRef): String? = when (val result = secrets.read(ref)) {
        is SecretReadResult.Found -> result.value.use { it.revealText() }
        else -> null
    }

    suspend fun storedProfile(): ServerProfile? = profiles.list().firstOrNull { it.id == SERVER_ID }

    val connectionState: ConnectionState get() = manager.state.value
}
