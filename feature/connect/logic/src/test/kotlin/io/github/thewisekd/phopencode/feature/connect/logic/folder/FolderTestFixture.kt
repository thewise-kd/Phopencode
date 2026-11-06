package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionOptions
import io.github.thewisekd.phopencode.core.remote.connection.Diagnostics
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
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal const val HOST = "server.example"
internal const val PORT = 22
internal const val USER = "tester"

internal val PROFILE_ID = ServerProfileId("folder-profile")
private val SSH_REF = SecretRef("folder-ssh-login")
private val OPENCODE_REF = SecretRef("folder-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
internal val SSH_PASSWORD = "test-" + "ssh-" + "login"
internal val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

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

internal val OTHER_PROFILE_ID = ServerProfileId("other-profile")

/** A second profile; it reaches the same fake server, so only the profile tells the two apart. */
internal val OTHER_PROFILE = PROFILE.copy(id = OTHER_PROFILE_ID, name = "Other server")

/** A running `opencode serve` is always found, so a connect walks straight to Connected. */
private object RunningServer : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/**
 * An SFTP client that counts its closes, and reports [SftpLog.reportedHome] when one is set. Once
 * closed, a call fails with [IllegalStateException], as `SshjSftpClient` does.
 */
internal class TrackedSftp(val inner: FakeSftpClient, private val log: SftpLog) : SftpClient by inner {
    val closes = AtomicInteger()

    val homeCalls = AtomicInteger()

    override suspend fun home(): String {
        homeCalls.incrementAndGet()
        checkOpen()
        val reported = inner.home()
        return log.reportedHome ?: reported
    }

    override suspend fun list(path: String): List<FileEntry> {
        log.beforeList.getAndSet(null)?.invoke(this)
        checkOpen()
        log.failNextList.getAndSet(null)?.let { throw it }
        return inner.list(path)
    }

    override suspend fun stat(path: String): FileEntry {
        log.beforeStat.getAndSet(null)?.invoke(this)
        checkOpen()
        return inner.stat(path)
    }

    private fun checkOpen() = check(closes.get() == 0) { "The SFTP client is closed" }

    override fun close() {
        closes.incrementAndGet()
        inner.close()
    }
}

/** Every SFTP client opened through the connection, and the roots asked for. */
internal class SftpLog {
    val opened = mutableListOf<TrackedSftp>()
    val roots = mutableListOf<String>()

    /** Replaces the fake's answer to `home()`, to report text the fake would not. */
    @Volatile
    var reportedHome: String? = null

    /** The next listing, of any client, fails with this; unlike `failNext` it takes any exception. */
    val failNextList = AtomicReference<Exception?>(null)

    /** Runs once, at the start of the next listing of any client. */
    val beforeList = AtomicReference<(suspend (TrackedSftp) -> Unit)?>(null)

    /** Runs once, at the start of the next `stat` of any client. */
    val beforeStat = AtomicReference<(suspend (TrackedSftp) -> Unit)?>(null)

    /** The first request of every client opened from now on fails with this. */
    @Volatile
    var failFirstRequest: SshException? = null
}

private class LoggingSession(private val inner: FakeSshSession, private val log: SftpLog) : SshSession by inner {
    override suspend fun openSftp(root: String): SftpClient {
        synchronized(log) { log.roots += root }
        val client = inner.openSftp(root) as FakeSftpClient
        log.failFirstRequest?.let(client::failNext)
        return TrackedSftp(client, log).also { synchronized(log) { log.opened += it } }
    }

    override fun close() = inner.close()
}

private class LoggingConnector(private val real: FakeSshConnector, private val log: SftpLog) : SshConnector {
    override suspend fun connect(
        target: SshTarget,
        credential: SshCredential,
        hostKeyVerifier: HostKeyVerifier,
        options: SshConnectOptions,
    ): SshSession = LoggingSession(real.connect(target, credential, hostKeyVerifier, options) as FakeSshSession, log)
}

/**
 * A [ConnectionManager] on fakes, whose sessions all share one [files] tree, so a reconnect sees
 * the same server.
 */
internal class FolderConnection(scope: CoroutineScope, diagnostics: Diagnostics = Diagnostics.Off) {
    val files = FakeRemoteFiles()
    val log = SftpLog()
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    val connector = FakeSshConnector(SERVER_KEY, newSession = { FakeSshSession(files = files) })

    /** Every API client the manager made, oldest first. */
    val apiClients = mutableListOf<FakeOpencodeClient>()

    /** When set, the first call of every API client made from now on fails with this. */
    @Volatile
    var nextClientFailure: ApiError? = null

    /** When set, the event stream of every API client made from now on fails with what this makes. */
    @Volatile
    var breakEvents: (() -> Exception)? = null

    val manager = ConnectionManager(
        connector = LoggingConnector(connector, log),
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = OpencodeClientFactory { _, _, _ ->
            val client = FakeOpencodeClient().also { client ->
                nextClientFailure?.let(client.control::failNext)
                synchronized(apiClients) { apiClients += client }
            }
            val failure = breakEvents
            if (failure == null) {
                client
            } else {
                object : OpencodeClient by client {
                    override val events: EventApi = object : EventApi by client.events {
                        override fun events(types: Set<String>): Flow<ServerEvent> = flow { throw failure() }
                    }
                }
            }
        },
        scope = scope,
        options = ConnectionOptions(reconnect = ReconnectPolicy(delays = listOf(1.seconds), maxAttempts = 3)),
        diagnostics = diagnostics,
        endLog = {},
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

    /** The SFTP client of the latest open, to inject failures into. */
    fun lastClient(): FakeSftpClient = synchronized(log) { log.opened.last().inner }

    fun failNextSftp(failure: SshException) = lastClient().failNext(failure)

    /** The API client of the connection standing now, or of the latest connect. */
    fun lastApiClient(): FakeOpencodeClient = synchronized(apiClients) { apiClients.last() }
}
