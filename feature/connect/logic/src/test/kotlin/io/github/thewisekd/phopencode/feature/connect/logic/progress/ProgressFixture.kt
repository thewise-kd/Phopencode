package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionEndLog
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.Diagnostics
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
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import java.io.IOException
import java.util.WeakHashMap
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    opencodePort: Int? = null,
) = ServerProfile(
    id = id,
    name = "Test server",
    host = HOST,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = opencodeCredential,
    opencodePort = opencodePort,
)

/** Plays server discovery and startup, and records every start. */
internal class FakeServerTools : ServerTools {
    @Volatile
    var server: ServerLookup = ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    @Volatile
    var binary: BinaryLookup =
        BinaryLookup.Found("/opt/opencode/bin/opencode", BinarySource.KnownLocation, VersionCheck.Unparseable)

    val starts = mutableListOf<ServerStartOptions>()

    /** The port each lookup was asked for. */
    val ports = mutableListOf<Int?>()

    override suspend fun findServer(session: SshSession): ServerLookup = findServer(session, null)

    override suspend fun findServer(session: SshSession, port: Int?): ServerLookup {
        ports += port
        return server
    }

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

/**
 * How long, in real time, a test waits for the presenters' cleanup: a disconnect returns only once
 * the stopped connection's release, which runs on a real IO thread, has finished.
 */
internal val CLEANUP_WAIT = 10.seconds

/** How often a wait for an event looks again, in real time. */
private val CLEANUP_POLL = 5.milliseconds

/** The fixtures each running test made, so that [runProgressTest] ends their screens. */
private val fixturesByTest = WeakHashMap<TestScope, MutableList<ProgressFixture>>()

/**
 * [runTest] for a test that makes presenters: once [body] returns, or fails, every screen its
 * fixtures made ends, and the test waits until each one's cleanup has finished (see
 * [ProgressFixture.endScreens]).
 *
 * Why: every progress presenter in the JVM shares one lock, and a screen's cleanup holds it while
 * its disconnect waits for a release on a real IO thread. A cleanup left to the end of [runTest]
 * resumes after the test's scheduler has stopped, so it never ends and keeps the lock held, and
 * every later test that connects waits for it in vain.
 */
internal fun runProgressTest(timeout: Duration? = null, body: suspend TestScope.() -> Unit): TestResult {
    val test: suspend TestScope.() -> Unit = {
        try {
            body()
        } catch (failure: Throwable) {
            try {
                endFixtures()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
        endFixtures()
    }
    return if (timeout == null) runTest(testBody = test) else runTest(timeout = timeout, testBody = test)
}

private suspend fun TestScope.endFixtures() {
    val made = synchronized(fixturesByTest) { fixturesByTest.remove(this) }.orEmpty()
    for (fixture in made) fixture.endScreens()
}

/**
 * A real [ConnectionManager] over fakes, and the stores the presenter uses. [diagnostics] are off
 * unless a test turns them on, as in a release build; the end log is silent.
 */
internal class ProgressFixture(val test: TestScope, diagnostics: Diagnostics = Diagnostics.Off) {
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
        diagnostics = diagnostics,
        endLog = ConnectionEndLog { },
    )

    private var ids = 0

    /** The job of every screen scope made, also those a test gave a dispatcher of its own. */
    private val screens = mutableListOf<Job>()

    init {
        connector.addServer(SshTarget(HOST, ServerProfile.DEFAULT_SSH_PORT, USER))
        synchronized(fixturesByTest) { fixturesByTest.getOrPut(test) { mutableListOf() } += this }
    }

    /** A scope of its own, so that a test can end it like a screen going away. */
    fun screenScope(): CoroutineScope {
        val parent = test.backgroundScope.coroutineContext
        val job = Job(parent[Job])
        synchronized(screens) { screens += job }
        return CoroutineScope(parent + job)
    }

    /**
     * Ends every screen this fixture made, as leaving them would, and waits until each one's
     * cleanup, the Cancel's included, has finished, and with it every disconnect it made and the
     * release that disconnect waits for. Fails if that takes longer than [CLEANUP_WAIT].
     */
    suspend fun endScreens() {
        val all = synchronized(screens) { screens.toList() }
        all.forEach { it.cancel() }
        awaitScreens(all)
    }

    /**
     * Waits until the screen [scope], which the test has ended, has finished its cleanup, and with
     * it any disconnect and the release on a real thread that disconnect waits for; then runs what
     * that made ready. Fails if that takes longer than [CLEANUP_WAIT].
     */
    suspend fun awaitEnded(scope: CoroutineScope) {
        val job = checkNotNull(scope.coroutineContext[Job]) { "a screen scope has a job" }
        check(job.isCancelled) { "the screen was not ended" }
        awaitScreens(listOf(job))
    }

    /**
     * Waits until [events] holds [event], which a presenter sends once its cleanup has finished,
     * as a Cancel's [ProgressEvent.Close]; then runs what that made ready. Fails if that takes
     * longer than [CLEANUP_WAIT].
     */
    suspend fun awaitEvent(events: List<ProgressEvent>, event: ProgressEvent) {
        val start = TimeSource.Monotonic.markNow()
        // Read on the test's thread, where the collector adds; the test's scheduler runs meanwhile.
        while (event !in events && start.elapsedNow() < CLEANUP_WAIT) {
            withContext(Dispatchers.Default) { delay(CLEANUP_POLL) }
        }
        check(event in events) { "no $event within $CLEANUP_WAIT" }
        test.runCurrent()
    }

    /**
     * Waits in real time, off the test's thread, so that the test's scheduler keeps running and the
     * cleanups resume on it once their releases are done.
     */
    private suspend fun awaitScreens(jobs: List<Job>) {
        val done = withContext(Dispatchers.Default) { withTimeoutOrNull(CLEANUP_WAIT) { jobs.joinAll() } }
        checkNotNull(done) { "a screen's cleanup did not finish within $CLEANUP_WAIT" }
        test.runCurrent()
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

    /** Every state [presenter] shows, collected as it comes, the current one first. */
    fun states(presenter: DefaultProgressPresenter): List<ProgressState> {
        val seen = mutableListOf<ProgressState>()
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
            presenter.state.collect { seen += it }
        }
        return seen
    }

    /**
     * Accepts the changed host key the manager shows now, as the host key screen does. The manager
     * takes over at once, before this returns; the reconnect runs on the test's dispatcher.
     */
    fun acceptChangedKey() {
        val shown = manager.state.value as ConnectionState.Error
        test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { manager.acceptChangedHostKey(shown) }
        check(manager.state.value is ConnectionState.Ssh) { "the accepted key did not start a reconnect" }
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
