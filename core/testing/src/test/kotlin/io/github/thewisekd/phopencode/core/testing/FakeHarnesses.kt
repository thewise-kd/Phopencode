package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.VcsBranch
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionActivity
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.core.testing.contract.ApiHarness
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_CAT
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_FIVE_EACH
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_FLOODS_STDERR
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_FLOODS_STDOUT
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_KILLS_ITSELF
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_PRINTS_AND_FAILS
import io.github.thewisekd.phopencode.core.testing.contract.EXEC_RUNS_ON
import io.github.thewisekd.phopencode.core.testing.contract.HostKeyVerifierHarness
import io.github.thewisekd.phopencode.core.testing.contract.RemoteExecHarness
import io.github.thewisekd.phopencode.core.testing.contract.SecretStoreHarness
import io.github.thewisekd.phopencode.core.testing.contract.SettingsHarness
import io.github.thewisekd.phopencode.core.testing.contract.SftpHarness
import io.github.thewisekd.phopencode.core.testing.contract.SshConnectorHarness
import io.github.thewisekd.phopencode.core.testing.contract.SshSessionHarness
import io.github.thewisekd.phopencode.core.testing.contract.StoragePart
import io.github.thewisekd.phopencode.core.testing.ssh.FakeHostKeyVerifier
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeKnownHostsStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeProfileRepository
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import io.github.thewisekd.phopencode.core.testing.storage.FakeSettingsRepository
import java.net.InetSocketAddress
import kotlinx.coroutines.awaitCancellation

/** Waits until this thread is parked on a monitor, or has ended. */
internal fun Thread.awaitBlockedOrDone() {
    val deadline = System.nanoTime() + 10_000_000_000L
    while (state != Thread.State.BLOCKED && state != Thread.State.TERMINATED) {
        check(System.nanoTime() < deadline) { "Thread neither blocked nor ended" }
        Thread.onSpinWait()
    }
}

internal class FakeApiHarness(override val client: FakeOpencodeClient = FakeOpencodeClient()) : ApiHarness {
    override val defaultFolder = Location(client.interactions.defaultDirectory)

    /** The fake's stream has no heartbeat to leak: the real implementation's decoder is what this tests. */
    override suspend fun serveHeartbeat() = Unit

    override suspend fun givenServerVersion(version: String) {
        client.serverVersion = version
    }

    override suspend fun givenFailure(error: ApiError) = client.control.failNext(error)

    override suspend fun stallCalls() = client.control.stall()

    override suspend fun awaitRunningCalls(count: Int) = client.control.awaitRunningCalls(count)

    override suspend fun givenSession(session: Session) = client.sessions.seed(session)

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) =
        client.messages.seed(sessionId, messages)

    override suspend fun givenRunning(sessionId: SessionId) =
        client.sessions.setActivity(sessionId, SessionActivity.Running)

    override suspend fun givenForm(form: Form) = client.interactions.seedForm(form)

    override suspend fun givenPermission(request: PermissionRequest) = client.interactions.seedPermission(request)

    override suspend fun givenCommands(commands: List<Command>, location: Location?) =
        client.catalog.setCommands(commands, location)

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) = client.fs.seed(location, files)

    override suspend fun givenBranches(location: Location, branches: List<String>) =
        client.vcs.setBranches(location, branches.map(::VcsBranch))

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) =
        client.messages.setTurnDiff(sessionId, from, diffs)

    override suspend fun serveEvent(event: ServerEvent) = client.events.emit(event)

    override suspend fun dropEventConnections() = client.events.drop()

    override suspend fun acceptReconnects() = client.events.reconnect()

    override suspend fun refuseEventConnections(error: ApiError) {
        client.events.refuseConnections(error)
        client.events.fail(error)
    }

    override suspend fun awaitEventConnections(count: Int) = client.events.awaitConnections(count)

    override suspend fun eventConnections(): Int = client.events.activeConnections.value
}

internal class FakeHostKeyVerifierHarness : HostKeyVerifierHarness {
    private var answer = HostKeyDecision.Reject
    private val fake = FakeHostKeyVerifier { _, _ -> answer }

    override val verifier = fake
    override val target = SshTarget("server.invalid", 22, "user")

    override suspend fun givenKnown(key: HostKey) = fake.trust(target, key)

    override suspend fun givenUserAnswers(decision: HostKeyDecision) {
        answer = decision
    }

    override suspend fun timesAsked(): Int = fake.asked.size
}

internal class FakeSshConnectorHarness : SshConnectorHarness {
    override val target = SshTarget("server.invalid", 22, "user")
    override val unreachable = SshTarget("unreachable.invalid", 22, "user")
    override val serverKey = HostKey("ssh-ed25519", byteArrayOf(9, 9, 9))
    private val password = "test-password"

    private val fake = FakeSshConnector(serverKey) { _, credential ->
        credential is SshCredential.Password && credential.password.concatToString() == password
    }.apply { addServer(target) }

    override val connector = fake

    override fun goodCredential() = SshCredential.Password(password.toCharArray())

    override fun badCredential() = SshCredential.Password("wrong".toCharArray())

    override suspend fun givenSilentServer() {
        fake.silent = true
    }

    override suspend fun credentialsReceived(): Int = fake.credentialsReceived

    override suspend fun halfOpenConnections(): Int = fake.halfOpenConnections
}

/** Scripts the commands of the contract as the real shell would run them. */
internal fun FakeRemoteExec.scriptContractCommands() {
    on(EXEC_PRINTS_AND_FAILS) {
        stdout("out")
        stderr("err")
        exit(3)
    }
    on(EXEC_KILLS_ITSELF) { killedBy("KILL") }
    on(EXEC_CAT) {
        stdin?.let { stdout(it) }
        exit(0)
    }
    on(EXEC_RUNS_ON) {
        stdout("ready")
        awaitCancellation()
    }
    on(EXEC_FIVE_EACH) {
        stdout("12345")
        stderr("12345")
        exit(0)
    }
    on(EXEC_FLOODS_STDOUT) {
        stdout("123456")
        awaitCancellation()
    }
    on(EXEC_FLOODS_STDERR) {
        stderr("123456")
        awaitCancellation()
    }
}

internal class FakeRemoteExecHarness : RemoteExecHarness {
    override val exec = FakeRemoteExec().apply { scriptContractCommands() }

    override suspend fun isRunning(command: String): Boolean = exec.isRunning(command)

    override suspend fun awaitRunning(command: String) = exec.awaitRunning(command)

    override suspend fun timesStarted(command: String): Int = exec.executions.count { it.command == command }

    override suspend fun disconnect() = exec.disconnect()
}

internal class FakeSshSessionHarness : SshSessionHarness {
    private val fake = FakeSshSession().apply {
        exec.scriptContractCommands()
        files.addDirectory("/srv/root")
    }

    override val session = fake
    override val sftpRoot = "/srv/root"
    override val missingRoot = "/srv/missing"
    override val tunnelAddress: InetSocketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 4096)

    override suspend fun awaitRunning(command: String) = fake.exec.awaitRunning(command)

    override suspend fun isRunning(command: String): Boolean = fake.exec.isRunning(command)

    override suspend fun loseConnection() = fake.loseConnection()

    override suspend fun tunnelsOpened(): Int = fake.tunnels.size
}

internal class FakeSftpHarness : SftpHarness {
    private val files = FakeRemoteFiles().apply {
        addDirectory(ROOT)
        addDirectory("$ROOT-sibling")
        addDirectory(OUTSIDE)
        addFile("$OUTSIDE/${SftpHarness.OUTSIDE_FILE}", "outside".encodeToByteArray())
    }
    private val clients = mutableListOf<FakeSftpClient>()

    override val root = ROOT
    override val outside = OUTSIDE
    override val client = newClient(ROOT)

    private fun newClient(root: String) = FakeSftpClient(root, files).also { clients += it }

    private fun inRoot(path: String) = if (path.isEmpty()) ROOT else "$ROOT/$path"

    override suspend fun givenFile(path: String, content: ByteArray, mode: Int?) {
        if (mode == null) files.addFile(inRoot(path), content) else files.addFile(inRoot(path), content, mode)
    }

    override suspend fun givenFolder(path: String) = files.addDirectory(inRoot(path))

    override suspend fun givenSymlink(path: String, target: String) = files.addSymlink(inRoot(path), target)

    override suspend fun givenDenied(path: String) = files.deny(inRoot(path))

    override suspend fun content(path: String): ByteArray? = files.content(inRoot(path))

    override suspend fun outsideContent(): ByteArray = files.content("$OUTSIDE/${SftpHarness.OUTSIDE_FILE}")!!

    override suspend fun exists(path: String): Boolean = files.exists(inRoot(path))

    override suspend fun mode(path: String): Int = files.mode(inRoot(path))

    override suspend fun names(path: String): Set<String> = files.names(inRoot(path))

    override suspend fun outsideNames(): Set<String> = files.names(OUTSIDE)

    override suspend fun clientAt(root: String) = newClient(root)

    override fun disconnect() = clients.forEach(FakeSftpClient::disconnect)

    private companion object {
        const val ROOT = "/srv/root"
        const val OUTSIDE = "/srv/outside"
    }
}

internal class FakeSecretStoreHarness : SecretStoreHarness {
    override val store = FakeSecretStore()

    override suspend fun givenUnreadable(ref: SecretRef) = store.seedUnreadable(ref)

    override suspend fun setReachable(reachable: Boolean) {
        store.unavailable = !reachable
    }
}

internal class FakeSettingsHarness : SettingsHarness {
    override val secrets = FakeSecretStore()
    override val profiles = FakeProfileRepository()
    override val knownHosts = FakeKnownHostsStore()
    override val settings = FakeSettingsRepository(secrets, profiles, knownHosts)

    override suspend fun givenUnreadableSecret(ref: SecretRef) = secrets.seedUnreadable(ref)

    override suspend fun setFailingPart(part: StoragePart?) {
        secrets.unavailable = part == StoragePart.Secrets
        profiles.unavailable = part == StoragePart.Profiles
        knownHosts.unavailable = part == StoragePart.KnownHosts
        settings.unavailable = part == StoragePart.Settings
    }
}
