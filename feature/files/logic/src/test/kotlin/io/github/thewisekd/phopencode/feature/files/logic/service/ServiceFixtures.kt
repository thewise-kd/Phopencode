package io.github.thewisekd.phopencode.feature.files.logic.service

import io.github.thewisekd.phopencode.core.api.FileSearchType
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.files.ContentMatch
import io.github.thewisekd.phopencode.core.files.ContentQuery
import io.github.thewisekd.phopencode.core.files.ContentSearchResult
import io.github.thewisekd.phopencode.core.files.FileContent
import io.github.thewisekd.phopencode.core.files.FileService
import io.github.thewisekd.phopencode.core.files.FileWriteOptions
import io.github.thewisekd.phopencode.core.files.NameSearchResult
import io.github.thewisekd.phopencode.core.files.SearchTool
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
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
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
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
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val HOST = "server.example"
private const val OTHER_HOST = "other-server.example"
private const val PORT = 22
private const val USER = "tester"

private val SSH_REF = SecretRef("service-ssh-login")
private val OPENCODE_REF = SecretRef("service-opencode-login")

/** Assembled at runtime, so that no literal looks like a real secret. */
private val SSH_PASSWORD = "test-" + "ssh-" + "login"
private val OPENCODE_PASSWORD = "test-" + "opencode-" + "login"

private val SERVER_KEY = HostKey("ssh-ed25519", ByteArray(32) { it.toByte() })

internal val SERVICE_PROFILE = ServerProfile(
    id = ServerProfileId("service-profile"),
    name = "Test server",
    host = HOST,
    port = PORT,
    username = USER,
    auth = SshAuth.Password(SSH_REF),
    opencodeCredential = OPENCODE_REF,
)

/** A second server, with an ID and host of its own. */
internal val OTHER_PROFILE = SERVICE_PROFILE.copy(id = ServerProfileId("service-profile-2"), host = OTHER_HOST)

private object RunningServer : ServerTools {
    override suspend fun findServer(session: SshSession): ServerLookup =
        ServerLookup.Running("127.0.0.1", 4096, 100, ServerSource.ProcessScan)

    override suspend fun findBinary(session: SshSession): BinaryLookup = error("Not used")

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer =
        error("Not used")
}

/** A [ConnectionManager] on fakes with two servers; [sessions] and [clients] are in connect order. */
internal class ServiceConnection(scope: CoroutineScope) {
    private val secrets = FakeSecretStore()
    private val knownHosts = FakeKnownHostsStore()
    private val connector = FakeSshConnector(SERVER_KEY)
    private val clientList = mutableListOf<FakeOpencodeClient>()

    private val opens = mutableListOf<SshSession>()

    /** When set, every SFTP open waits here, even when cancelled, before it reaches its session. */
    @Volatile
    var sftpGate: CompletableDeferred<Unit>? = null

    /** The session of every SFTP open asked for, in order. */
    val sftpOpens: List<SshSession>
        get() = synchronized(opens) { opens.toList() }

    /** Records and holds SFTP opens; everything else goes to the fake session as it is. */
    private val gatedConnector = object : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            val inner = connector.connect(target, credential, hostKeyVerifier, options)
            return object : SshSession by inner {
                override suspend fun openSftp(root: String): SftpClient {
                    sftpGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
                    synchronized(opens) { opens += inner }
                    return inner.openSftp(root)
                }
            }
        }
    }

    val manager = ConnectionManager(
        connector = gatedConnector,
        secrets = secrets,
        knownHosts = knownHosts,
        servers = ServerResolver(secrets, RunningServer),
        clients = OpencodeClientFactory { _, _, _ ->
            FakeOpencodeClient().also { synchronized(clientList) { clientList += it } }
        },
        scope = scope,
        options = ConnectionOptions(reconnect = ReconnectPolicy(delays = listOf(1.seconds), maxAttempts = 3)),
    )

    val sessions: List<FakeSshSession>
        get() = connector.sessions

    val clients: List<FakeOpencodeClient>
        get() = synchronized(clientList) { clientList.toList() }

    init {
        connector.addServer(SshTarget(HOST, PORT, USER))
        connector.addServer(SshTarget(OTHER_HOST, PORT, USER))
    }

    /** Seeds the login and the host keys, then starts the connect; the caller runs the scheduler. */
    suspend fun connect(profile: ServerProfile = SERVICE_PROFILE) {
        SecretValue.ofText(SSH_PASSWORD).use { secrets.put(SSH_REF, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }
        val fingerprint = HostKeyFingerprint(SERVER_KEY.fingerprintSha256)
        for (host in listOf(HOST, OTHER_HOST)) {
            if (knownHosts.approved(HostEndpoint(host, PORT)) == null) knownHosts.approve(HostEndpoint(host, PORT), fingerprint)
        }
        manager.connect(profile)
    }
}

/** What one call of a [ServiceConnection]-backed adapter got from the factory. */
internal class Opened(val sftp: SftpClient, val exec: RemoteExec, val fs: FsApi)

/**
 * A [FileService] a test scripts: every call is recorded in [calls], waits at [gate] when it is set,
 * then throws [failure] when it is set.
 */
internal class ScriptedFileService(override val root: String) : FileService {
    val calls = mutableListOf<String>()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    @Volatile
    var failure: Throwable? = null

    var statEntry = FileEntry("$root/a.txt", FileEntryType.File, sizeBytes = 3, modifiedAt = Instant.fromEpochSeconds(1_700_000_000))
    var matches = listOf<ContentMatch>()
    var names = listOf<FileEntry>()
    var lastQuery: ContentQuery? = null
    var lastNameLimit: Int? = null
    var lastWriteOptions: FileWriteOptions? = null

    private suspend fun step(name: String) {
        synchronized(calls) { calls += name }
        gate?.await()
        failure?.let { throw it }
    }

    override suspend fun home(): String {
        step("home")
        return root
    }

    override suspend fun list(path: String): List<FileEntry> {
        step("list")
        return listOf(statEntry)
    }

    override suspend fun stat(path: String): FileEntry {
        step("stat")
        return statEntry
    }

    override suspend fun lstat(path: String): FileEntry {
        step("lstat")
        return statEntry
    }

    override suspend fun read(path: String, maxBytes: Long): FileContent {
        step("read")
        return FileContent(statEntry, byteArrayOf(1, 2, 3))
    }

    override suspend fun write(path: String, content: ByteArray, options: FileWriteOptions) {
        step("write")
        lastWriteOptions = options
    }

    override suspend fun upload(path: String, source: InputStream, options: FileWriteOptions, onProgress: (Long) -> Unit) {
        step("upload")
        lastWriteOptions = options
    }

    override suspend fun download(path: String, sink: OutputStream, onProgress: (Long) -> Unit): Long {
        step("download")
        return 0
    }

    override suspend fun createDirectory(path: String, parents: Boolean) = step("createDirectory")

    override suspend fun delete(path: String, recursive: Boolean) = step("delete")

    override suspend fun move(from: String, to: String, overwrite: Boolean) = step("move")

    override suspend fun findByName(directory: String, query: String, type: FileSearchType?, limit: Int): NameSearchResult {
        step("findByName")
        lastNameLimit = limit
        return NameSearchResult(names, truncated = false)
    }

    override suspend fun searchContent(directory: String, query: ContentQuery): ContentSearchResult {
        step("searchContent")
        lastQuery = query
        return ContentSearchResult(matches, SearchTool.Grep, truncated = false, incomplete = false)
    }
}
