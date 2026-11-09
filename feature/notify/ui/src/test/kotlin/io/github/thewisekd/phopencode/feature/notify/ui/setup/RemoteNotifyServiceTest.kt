package io.github.thewisekd.phopencode.feature.notify.ui.setup

import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.remote.connection.BoundConnection
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.connection.OpencodeLogin
import io.github.thewisekd.phopencode.core.remote.notify.NotifyHelperFile
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallException
import io.github.thewisekd.phopencode.core.remote.notify.NotifyInstallFailure
import io.github.thewisekd.phopencode.core.remote.notify.OpencodeAccessSource
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyTopic
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Not used: ${method.name}")
    } as T

/**
 * A server played by a function of the command line: it answers the installer's probe, `mkdir -m 700`
 * and the scripts, and records every command. Files live in [files], shared with the SFTP fake.
 */
private class FakeServer(val files: FakeRemoteFiles) : RemoteExec {
    val commands = mutableListOf<String>()
    val stdins = mutableListOf<ByteArray?>()
    var answer: (String) -> Pair<String, Int> = { command -> defaultAnswer(command) }

    /** Runs before each command is answered, after it is recorded; may suspend to hold it. */
    var hold: suspend (String) -> Unit = {}

    var python = "3.12"
    var installExit = 0
    var sendTestExit = 0
    var statusLine = "status systemd running"
    var failure: Exception? = null

    fun defaultAnswer(command: String): Pair<String, Int> = when {
        "python3 -c" in command -> "python $python\nconfig $CONFIG_BASE\n" to 0
        "mkdir -m 700" in command -> {
            val temp = "/tmp/" + nameIn(command)
            files.addDirectory(temp)
            "$temp\n" to 0
        }
        "rm -rf" in command -> {
            val temp = "/tmp/" + nameIn(command)
            if (files.exists(temp)) kotlinx.coroutines.runBlocking { FakeSftpClient("/", files).delete(temp, recursive = true) }
            "" to 0
        }
        "'--status'" in command -> "$statusLine\n" to 0
        "uninstall.sh" in command -> "uninstalled\n" to 0
        "mkdir" in command -> {
            files.addDirectory("$CONFIG_BASE/phopencode")
            "" to 0
        }
        "'--send-test'" in command -> "" to sendTestExit
        "install.sh" in command -> (if (installExit == 0) "installed systemd\n" else "") to installExit
        else -> "" to 127
    }

    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
        commands += request.command
        stdins += request.stdin
        failure?.let { throw it }
        hold(request.command)
        val (stdout, exit) = answer(request.command)
        return ExecResult(stdout.encodeToByteArray(), ByteArray(0), exit, null)
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = error("Not used")

    companion object {
        /** The private folder name the installer drew, the last argument of its make and remove commands. */
        fun nameIn(command: String): String =
            Regex("""'(phopencode-notify\.[A-Za-z0-9]+)'$""").find(command)!!.groupValues[1]
        const val CONFIG_BASE = "/home/fake-user/.config"
    }
}

/**
 * A connection that keeps a login ref, as the real one does, and reads the password through it
 * from [stored] on each call; every value it hands out is recorded in [issued].
 */
private class FakeConnection(
    override val exec: RemoteExec,
    private val files: FakeRemoteFiles,
    override val generation: Long = 1,
) : BoundConnection {
    var sftpFailure: SshException? = null
    var login: OpencodeLogin? = OpencodeLogin("http://127.0.0.1:4096", SecretRef("notify-test-ref"))
    var stored: String? = null
    val issued = mutableListOf<SecretValue>()

    override val opencode: OpencodeLogin? get() = login

    override suspend fun readOpencodePassword(): SecretValue? =
        stored?.let { SecretValue.ofText(it) }?.also { issued += it }

    override val fs: FsApi = unused()
    override val server: ConnectionServer get() = error("Not used")

    override suspend fun openSftp(root: String): SftpClient {
        sftpFailure?.let { throw it }
        return FakeSftpClient(root, files)
    }
}

/**
 * The adapter maps the installer's results and failures onto the port, runs on the standing
 * connection only, keeps secrets out of commands and messages, and uploads the files the build
 * bundles.
 */
class RemoteNotifyServiceTest {
    private val files = FakeRemoteFiles()
    private val server = FakeServer(files)
    private val connection = FakeConnection(server, files)
    private var connected = true

    /** The connection standing now; a test may replace it, as a reconnect would. */
    private var current: BoundConnection = connection
    private val connections = object : RemoteNotifyService.Connections {
        override suspend fun <T> withConnection(block: suspend (BoundConnection) -> T): T {
            if (!connected) throw NotConnectedException()
            return block(current)
        }
    }

    // Obvious fake values built at runtime: none is a real secret or address.
    private val topicText = "fakeTopic" + List(20) { ('A'..'Z').random() }.joinToString("")
    private val passwordText = "fake-pw-" + List(10) { ('a'..'z').random() }.joinToString("")
    private val settings = NtfySettings(NtfyServer.DEFAULT, NtfyTopic.of(topicText)!!)
    private val issued get() = connection.issued
    private val bundle = NotifyHelperFile.entries.associateWith { "fake ${it.fileName}".encodeToByteArray() }

    private val service = RemoteNotifyService(
        connections,
        { file -> bundle.getValue(file).inputStream() },
        OpencodeAccessSource.FromConnection,
    )

    init {
        connection.stored = passwordText
    }

    private fun assertAllWiped() {
        assertTrue(issued.isNotEmpty())
        issued.forEach { value -> assertFailsWith<IllegalStateException> { value.revealText() } }
    }

    private fun assertNoTempLeft() {
        assertTrue(!files.exists("/tmp") || files.names("/tmp").isEmpty(), "no private folder is left")
    }

    @Test
    fun `each call draws a new private folder name and removes exactly that folder`() = runTest {
        service.status()
        service.status()

        val made = server.commands.filter { "mkdir -m 700" in it }.map { FakeServer.nameIn(it) }
        val removed = server.commands.filter { "rm -rf" in it }.map { FakeServer.nameIn(it) }
        assertEquals(2, made.toSet().size)
        assertEquals(made, removed)
        assertNoTempLeft()
    }

    private suspend fun failureOf(call: suspend () -> Unit): NotifyFailure =
        assertFailsWith<NotifyException> { call() }.failure

    @Test
    fun `install writes the config from the connection's access and closes that access`() = runTest {
        var config: JsonObject? = null
        server.answer = { command ->
            if (isStart(command)) {
                config = Json.parseToJsonElement(files.content(CONFIG)!!.decodeToString()) as JsonObject
            }
            server.defaultAnswer(command)
        }

        service.install(settings)

        val written = config!!
        assertEquals("http://127.0.0.1:4096", written.getValue("server_url").jsonPrimitive.content)
        assertEquals(passwordText, written.getValue("password").jsonPrimitive.content)
        assertEquals("https://ntfy.sh", written.getValue("ntfy_server").jsonPrimitive.content)
        assertEquals(topicText, written.getValue("ntfy_topic").jsonPrimitive.content)
        assertEquals(0b110_000_000, files.mode(CONFIG))
        assertEquals(1, issued.size, "read once, through the ref")
        assertAllWiped()
        assertNoTempLeft()
    }

    @Test
    fun `status maps every state`() = runTest {
        mapOf(
            "status systemd running" to NotifyServiceStatus.Running,
            "status cron stopped" to NotifyServiceStatus.Stopped,
            "status none stopped" to NotifyServiceStatus.NotInstalled,
        ).forEach { (line, expected) ->
            server.statusLine = line
            assertEquals(expected, service.status())
        }
        server.statusLine = "status none unknown"
        assertEquals(NotifyFailure.Unknown, failureOf { service.status() })
    }

    @Test
    fun `remove and sendTest run on the server`() = runTest {
        service.remove()
        service.sendTest(settings)

        assertTrue(server.commands.any { it.endsWith("uninstall.sh'") })
        assertTrue(server.commands.any { "'--send-test'" in it })
    }

    @Test
    fun `without a connection every call fails as not connected and nothing runs`() = runTest {
        connected = false

        listOf<suspend () -> Unit>(
            { service.status() },
            { service.install(settings) },
            { service.remove() },
            { service.sendTest(settings) },
        ).forEach { call -> assertEquals(NotifyFailure.NotConnected, failureOf(call)) }
        assertTrue(server.commands.isEmpty())
        assertTrue(issued.isEmpty())
    }

    @Test
    fun `python, ntfy and step failures map as the port says`() = runTest {
        server.python = "missing"
        assertEquals(NotifyFailure.PythonMissing, failureOf { service.install(settings) })
        assertEquals(NotifyFailure.PythonMissing, failureOf { service.sendTest(settings) })
        server.python = "3.8"
        assertEquals(NotifyFailure.PythonTooOld, failureOf { service.install(settings) })
        assertEquals(NotifyFailure.PythonTooOld, failureOf { service.sendTest(settings) })
        server.python = "3.12"

        server.installExit = 9
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.install(settings) })
        server.installExit = 3
        assertEquals(NotifyFailure.PythonMissing, failureOf { service.install(settings) })
        server.installExit = 4
        assertEquals(NotifyFailure.PythonTooOld, failureOf { service.install(settings) })
        server.sendTestExit = 3
        assertEquals(NotifyFailure.NtfyFailed, failureOf { service.sendTest(settings) })
        server.sendTestExit = 2
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.sendTest(settings) })
        assertAllWiped()
    }

    @Test
    fun `SSH failures map to not connected, unreachable or a failed step`() = runTest {
        server.failure = SshException.Disconnected()
        assertEquals(NotifyFailure.NotConnected, failureOf { service.remove() })
        server.failure = NotConnectedException()
        assertEquals(NotifyFailure.NotConnected, failureOf { service.remove() })
        server.failure = SshException.Timeout()
        assertEquals(NotifyFailure.Unreachable, failureOf { service.status() })
        server.failure = SshException.OutputLimitExceeded(1)
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.status() })
        server.failure = IllegalStateException("fake")
        assertEquals(NotifyFailure.Unknown, failureOf { service.status() })
        server.failure = null

        connection.sftpFailure = SshException.Sftp(SftpFailure.PermissionDenied)
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.remove() })
    }

    @Test
    fun `a connection without a login ref, or whose secret is gone, fails the install before anything runs`() = runTest {
        connection.stored = null
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.install(settings) })
        connection.stored = passwordText
        connection.login = null
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.install(settings) })

        assertTrue(server.commands.isEmpty())
        assertTrue(issued.isEmpty())
    }

    @Test
    fun `the password read for an install is wiped on failure and on cancellation too`() = runTest {
        server.installExit = 9
        assertEquals(NotifyFailure.InstallFailed, failureOf { service.install(settings) })
        assertAllWiped()

        server.installExit = 0
        server.answer = { command ->
            if (isStart(command)) throw CancellationException("fake")
            server.defaultAnswer(command)
        }
        assertFailsWith<CancellationException> { service.install(settings) }
        assertEquals(2, issued.size)
        assertAllWiped()
    }

    @Test
    fun `concurrent calls on the service never interleave on the server`() = runTest {
        val gate = CompletableDeferred<Unit>()
        server.hold = { command -> if (isStart(command)) gate.await() }

        val install = launch { service.install(settings) }
        runCurrent()
        val installCommands = server.commands.size
        assertTrue(isStart(server.commands.last()), "the install is held at its start")

        val remove = launch { service.remove() }
        val status = launch { service.status() }
        runCurrent()
        assertEquals(installCommands, server.commands.size, "nothing else runs while the install is under way")

        gate.complete(Unit)
        install.join()
        remove.join()
        status.join()

        // The install's commands end with removing its folder; only then does the next call start.
        val afterInstall = server.commands.drop(installCommands)
        assertTrue("rm -rf" in afterInstall.first(), afterInstall.first())
        val rest = afterInstall.drop(1)
        assertTrue("mkdir -m 700" in rest[0])
        assertTrue(rest[1].endsWith("uninstall.sh'"))
        assertTrue("rm -rf" in rest[2])
        assertTrue("mkdir -m 700" in rest[3])
        assertTrue("'--status'" in rest[4])
        assertTrue("rm -rf" in rest[5])
        assertEquals(6, rest.size)
    }

    @Test
    fun `a call queued behind another fails as not connected when the connection changed meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        server.hold = { command -> if (isStart(command)) gate.await() }
        val install = launch { service.install(settings) }
        runCurrent()
        val firstServerCommands = server.commands.size

        var queued: NotifyFailure? = null
        val remove = launch { queued = failureOf { service.remove() } }
        runCurrent()
        val newFiles = FakeRemoteFiles()
        val newServer = FakeServer(newFiles)
        current = FakeConnection(newServer, newFiles, generation = 2)
        gate.complete(Unit)
        install.join()
        remove.join()

        assertEquals(NotifyFailure.NotConnected, queued)
        assertTrue(newServer.commands.isEmpty(), "nothing ran on the new server")
        assertEquals(firstServerCommands + 1, server.commands.size, "the old server only saw the install end")
    }

    @Test
    fun `a call that fails releases the lock for the next one`() = runTest {
        server.python = "missing"
        assertEquals(NotifyFailure.PythonMissing, failureOf { service.install(settings) })
        server.python = "3.12"

        service.remove()
    }

    @Test
    fun `the connection's login prints no address and no ref`() {
        val text = connection.login.toString()
        assertFalse("127.0.0.1" in text)
        assertFalse("notify-test-ref" in text)
    }

    @Test
    fun `the installer's failures map one to one`() {
        val expected = mapOf(
            NotifyInstallFailure.PythonMissing to NotifyFailure.PythonMissing,
            NotifyInstallFailure.PythonTooOld to NotifyFailure.PythonTooOld,
            NotifyInstallFailure.StepFailed to NotifyFailure.InstallFailed,
            NotifyInstallFailure.NtfyRejected to NotifyFailure.NtfyFailed,
            NotifyInstallFailure.StatusUnknown to NotifyFailure.Unknown,
            NotifyInstallFailure.AccessUnavailable to NotifyFailure.InstallFailed,
        )
        assertEquals(NotifyInstallFailure.entries.toSet(), expected.keys)
        expected.forEach { (failure, mapped) ->
            assertEquals(mapped, RemoteNotifyService.failureOf(NotifyInstallException(failure)))
        }
    }

    @Test
    fun `cancellation passes through`() = runTest {
        server.failure = CancellationException("fake")

        assertFailsWith<CancellationException> { service.status() }
    }

    @Test
    fun `secrets never reach a command, stdin, a message or a printout`() = runTest {
        service.install(settings)
        server.sendTestExit = 3
        val refused = assertFailsWith<NotifyException> { service.sendTest(settings) }

        server.commands.forEach { command ->
            assertFalse(topicText in command)
            assertFalse(passwordText in command)
        }
        assertTrue(server.stdins.all { it == null })
        listOf(refused.message.orEmpty(), refused.toString(), service.toString(), settings.toString()).forEach { text ->
            assertFalse(topicText in text)
            assertFalse(passwordText in text)
        }
        assertNull(refused.cause)
    }

    @Test
    fun `the build bundles notifyd_pyz and both scripts of helper`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "helper/install.sh").isFile }

        val pyz = BundledNotifyHelper.open(NotifyHelperFile.Service).use { it.readBytes() }
        assertTrue(pyz.decodeToString(0, 22) == "#!/usr/bin/env python3")
        assertTrue(pyz.size > 1024)
        assertContentEquals(
            File(root, "helper/install.sh").readBytes(),
            BundledNotifyHelper.open(NotifyHelperFile.Install).use { it.readBytes() },
        )
        assertContentEquals(
            File(root, "helper/uninstall.sh").readBytes(),
            BundledNotifyHelper.open(NotifyHelperFile.Uninstall).use { it.readBytes() },
        )
    }

    private companion object {
        /** `sh <temp>/install.sh <temp>/notifyd.pyz`, the step that starts the service. */
        fun isStart(command: String): Boolean = "/install.sh'" in command && command.endsWith("/notifyd.pyz'")
        const val CONFIG = FakeServer.CONFIG_BASE + "/phopencode/notifyd.json"
    }
}
