package io.github.thewisekd.phopencode.feature.sessions.logic.create

import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
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
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeRecentFoldersStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionCreateEvent
import java.io.IOException
import javax.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal const val HOST = "server.example"
internal const val PORT = 22
internal const val USER = "tester"

internal val PROFILE_ID = ServerProfileId("create-profile")
internal val OTHER_PROFILE_ID = ServerProfileId("other-profile")
private val SSH_REF = SecretRef("create-ssh-login")
private val OPENCODE_REF = SecretRef("create-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

internal val PROFILE = ServerProfile(
    id = PROFILE_ID,
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A second profile on the same fake server: only the profile tells the two apart. */
internal val OTHER_PROFILE = PROFILE.copy(id = OTHER_PROFILE_ID, name = "Other server")

internal val BUILD = Agent(id = "build", name = "Build", mode = AgentMode.Primary)
internal val PLAN = Agent(id = "plan", name = "Plan", mode = AgentMode.All)
internal val GENERAL = Agent(id = "general", name = "General", mode = AgentMode.Subagent)
internal val TITLER = Agent(id = "title", name = "Title", mode = AgentMode.Primary, hidden = true)

internal val SONNET = Model(id = "sonnet", providerId = "anthropic", name = "Sonnet")
internal val GPT = Model(id = "gpt", providerId = "openai", name = "GPT", variants = listOf("high"))
internal val SONNET_REF = ModelRef("anthropic", "sonnet")
internal val GPT_REF = ModelRef("openai", "gpt")

/** A running `opencode serve` is always found, so a connect walks straight to Connected. */
private object RunningServer : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/** Makes one [FakeOpencodeClient] per connection, and keeps them. */
internal class RecordingClients : OpencodeClientFactory {
    val created = mutableListOf<FakeOpencodeClient>()

    /** Runs on every client before the connection hands it out, to fill in its catalog. */
    @Volatile
    var prepare: (FakeOpencodeClient) -> Unit = {}

    override fun create(baseUrl: String, password: SecretValue, sockets: SocketFactory): OpencodeClient =
        FakeOpencodeClient().also {
            prepare(it)
            synchronized(created) { created += it }
        }
}

/** [FakeRecentFoldersStore] whose writes or reads can fail on their own. */
internal class FlakyRecentFolders(private val inner: FakeRecentFoldersStore = FakeRecentFoldersStore()) :
    RecentFoldersStore by inner {
    @Volatile
    var failAdds: Boolean = false

    @Volatile
    var failObserve: Boolean = false

    override fun observe(profile: ServerProfileId): Flow<List<String>> {
        if (failObserve) throw IOException("Fake read failure")
        return inner.observe(profile)
    }

    /** When set, every add waits for it first. */
    @Volatile
    var addGate: CompletableDeferred<Unit>? = null

    override suspend fun add(profile: ServerProfileId, path: String) {
        addGate?.await()
        if (failAdds) throw IOException("Fake write failure")
        inner.add(profile, path)
    }
}

/** A real [ConnectionManager] over fakes, the stores, and a way to build the presenter. */
internal class CreateFixture(val test: TestScope) {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    val clients = RecordingClients()
    val recent = FlakyRecentFolders()
    private val connector = FakeSshConnector(SERVER_KEY)

    val manager = ConnectionManager(
        connector = connector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = clients,
        scope = test.backgroundScope,
    )

    init {
        connector.addServer(SshTarget(HOST, PORT, USER))
    }

    /** Seeds the login and the host key and starts the connect; the caller runs the scheduler. */
    suspend fun connect(profile: ServerProfile = PROFILE) {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        knownHosts.approve(HostEndpoint(HOST, PORT), HostKeyFingerprint(SERVER_KEY.fingerprintSha256))
        manager.connect(profile)
    }

    /** The client of the connection standing now. */
    val client: FakeOpencodeClient
        get() = (manager.state.value as ConnectionState.Connected).client as FakeOpencodeClient

    /** A scope of its own, so that a test can end it like a screen going away. */
    fun screenScope(): CoroutineScope {
        val parent = test.backgroundScope.coroutineContext
        return CoroutineScope(parent + Job(parent[Job]))
    }

    fun presenter(
        directory: String? = null,
        skillId: String? = null,
        scope: CoroutineScope = screenScope(),
    ) = DefaultSessionCreatePresenter(
        directory = directory,
        skillId = skillId,
        connection = manager,
        recentFolders = recent,
        scope = scope,
    )

    /** Every event [presenter] sends, collected as it comes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(presenter: DefaultSessionCreatePresenter): List<SessionCreateEvent> {
        val seen = mutableListOf<SessionCreateEvent>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            presenter.events.collect { seen += it }
        }
        return seen
    }
}
