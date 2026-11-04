package io.github.thewisekd.phopencode.core.remote.wiring

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.RemoteExecHarness
import io.github.thewisekd.phopencode.core.testing.contract.SftpHarness
import io.github.thewisekd.phopencode.core.testing.contract.SshConnectorHarness
import io.github.thewisekd.phopencode.core.testing.contract.SshSessionHarness
import io.github.thewisekd.phopencode.core.testing.contract.remoteExecContract
import io.github.thewisekd.phopencode.core.testing.contract.sftpClientContract
import io.github.thewisekd.phopencode.core.testing.contract.sshConnectorContract
import io.github.thewisekd.phopencode.core.testing.contract.sshSessionContract
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * The core/testing SSH contracts against the real implementation: the default [SshjConnector] and
 * what its sessions offer, over one embedded MINA server per case. They run here rather than in
 * core/ssh, because core/testing depends on core/ssh and the build refuses the edge back.
 */
class SshContractsTest {
    private val closeables = ConcurrentLinkedDeque<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables.addFirst(it) }

    private fun List<ContractCase>.asTests(): List<DynamicTest> = map { case ->
        DynamicTest.dynamicTest(case.name) {
            val servers = mutableListOf<WiringServer>()
            try {
                case.run()
            } finally {
                while (true) {
                    val next = closeables.pollFirst() ?: break
                    if (next is WiringServer) servers += next
                    runCatching { next.close() }
                }
            }
            // The server kills what it started; still, nothing may outlive a case.
            assertEquals(emptyList(), servers.flatMap { it.survivors() }, "Processes left running")
        }
    }

    @TestFactory
    fun sshConnector() = sshConnectorContract { ConnectorHarness() }.asTests()

    @TestFactory
    fun sshSession() = sshSessionContract { SessionHarness.create(this) }.asTests()

    @TestFactory
    fun remoteExec() = remoteExecContract { ExecHarness.create(this) }.asTests()

    @TestFactory
    fun sftpClient() = sftpClientContract { FilesHarness.create(this) }.asTests()

    internal suspend fun connect(server: WiringServer): SshSession =
        SshjConnector().connect(server.target, password(), ACCEPT, SshConnectOptions()).closeLater()

    internal fun server(): WiringServer = WiringServer().closeLater()

    private inner class ConnectorHarness : SshConnectorHarness {
        private val server = server()
        private var silent: SilentServer? = null

        override val connector = SshjConnector()
        override val target: SshTarget get() = silent?.target ?: server.target
        override val serverKey = server.hostKey
        override val unreachable = SshTarget("127.0.0.1", unusedPort(), TestSecrets.USER)

        override fun goodCredential(): SshCredential = password()

        override fun badCredential(): SshCredential = password(TestSecrets.WRONG_PASSWORD)

        override suspend fun givenSilentServer() {
            silent = SilentServer().closeLater()
        }

        override suspend fun credentialsReceived(): Int = server.credentialsReceived()

        /** The server and the silent listener see a closed socket a moment after the client. */
        override suspend fun halfOpenConnections(): Int =
            settled { server.unauthenticatedSessions() + (silent?.openConnections() ?: 0) }
    }

    private class ExecHarness(private val server: WiringServer, private val session: SshSession) : RemoteExecHarness {
        override val exec: RemoteExec get() = session.exec

        override suspend fun isRunning(command: String): Boolean = server.isRunning(command)

        override suspend fun awaitRunning(command: String) = awaitReal("$command runs") { server.isRunning(command) }

        override suspend fun timesStarted(command: String): Int = server.timesStarted(command)

        override suspend fun disconnect() {
            server.dropAll()
            awaitReal("the session sees the drop") { !session.connected.value }
        }

        companion object {
            suspend fun create(test: SshContractsTest): ExecHarness {
                val server = test.server()
                return ExecHarness(server, test.connect(server))
            }
        }
    }

    private class SessionHarness(
        private val server: WiringServer,
        override val session: SshSession,
        echo: EchoServer,
    ) : SshSessionHarness {
        override val sftpRoot: String = server.home.resolve("root").createDirectories().toString()
        override val missingRoot: String = server.home.resolve("missing").toString()
        override val tunnelAddress = InetSocketAddress(LOOPBACK, echo.port)

        override suspend fun awaitRunning(command: String) = awaitReal("$command runs") { server.isRunning(command) }

        override suspend fun isRunning(command: String): Boolean = server.isRunning(command)

        override suspend fun loseConnection() {
            server.dropAll()
            awaitReal("the session sees the drop") { !session.connected.value }
        }

        override suspend fun tunnelsOpened(): Int = server.tunnelsOpened()

        companion object {
            suspend fun create(test: SshContractsTest): SessionHarness {
                val server = test.server()
                val echo = with(test) { EchoServer().closeLater() }
                return SessionHarness(server, test.connect(server), echo)
            }
        }
    }

    private class FilesHarness(
        private val test: SshContractsTest,
        private val server: WiringServer,
        private val session: SshSession,
    ) : SftpHarness {
        private val rootPath: Path = server.home.resolve("root").createDirectories()
        private val outsidePath: Path = server.home.resolve("outside").createDirectories().also {
            it.resolve(SftpHarness.OUTSIDE_FILE).writeBytes("outside".encodeToByteArray())
        }

        override val root: String = rootPath.toString()
        override val outside: String = outsidePath.toString()

        lateinit var opened: SftpClient
        override val client: SftpClient get() = opened

        private fun local(path: String): Path = if (path.isEmpty()) rootPath else rootPath.resolve(path)

        override suspend fun givenFile(path: String, content: ByteArray, mode: Int?) {
            val file = local(path)
            file.writeBytes(content)
            if (mode != null) Files.setPosixFilePermissions(file, permissions(mode))
        }

        override suspend fun givenFolder(path: String) {
            local(path).createDirectories()
        }

        override suspend fun givenSymlink(path: String, target: String) {
            Files.createSymbolicLink(local(path), Path.of(target))
        }

        override suspend fun givenHome(path: String) {
            val home = Path.of(path)
            if (!Files.exists(home, NOFOLLOW_LINKS)) home.createDirectories()
            server.sftpHome = home
        }

        override suspend fun givenDenied(path: String) {
            Files.setPosixFilePermissions(local(path), emptySet())
        }

        override suspend fun content(path: String): ByteArray? =
            local(path).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }?.readBytes()

        override suspend fun outsideContent(): ByteArray = outsidePath.resolve(SftpHarness.OUTSIDE_FILE).readBytes()

        override suspend fun exists(path: String): Boolean = Files.exists(local(path), NOFOLLOW_LINKS)

        override suspend fun mode(path: String): Int = (Files.getAttribute(local(path), "unix:mode") as Int) and MODE_BITS

        override suspend fun names(path: String): Set<String> = namesIn(local(path))

        override suspend fun outsideNames(): Set<String> = namesIn(outsidePath)

        override suspend fun clientAt(root: String): SftpClient = with(test) { session.openSftp(root).closeLater() }

        override fun disconnect() {
            server.dropAll()
            val started = TimeSource.Monotonic.markNow()
            while (session.connected.value && started.elapsedNow() < WAIT) Thread.sleep(POLL_MILLIS)
        }

        private fun namesIn(folder: Path): Set<String> = Files.list(folder).use { list -> list.map { it.name }.toList().toSet() }

        companion object {
            suspend fun create(test: SshContractsTest): FilesHarness {
                val server = test.server()
                val harness = FilesHarness(test, server, test.connect(server))
                harness.opened = harness.clientAt(harness.root)
                return harness
            }

            private const val MODE_BITS = 0b111_111_111

            private fun permissions(mode: Int) = PosixFilePermissions.fromString(
                (8 downTo 0).joinToString("") { bit ->
                    if (mode and (1 shl bit) == 0) "-" else "rwx"[(8 - bit) % 3].toString()
                },
            )
        }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
        val WAIT: Duration = 10.seconds
        const val POLL_MILLIS = 20L

        /**
         * Waits in real time: a contract case runs under `runTest`, whose virtual clock would skip
         * the polling delay.
         */
        suspend fun awaitReal(what: String, condition: () -> Boolean) = withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > WAIT) throw AssertionError("Not within $WAIT: $what")
                Thread.sleep(POLL_MILLIS)
            }
        }

        /** [count] once it reaches zero, or its value after [WAIT]. */
        suspend fun settled(count: () -> Int): Int = withContext(Dispatchers.IO) {
            val started = TimeSource.Monotonic.markNow()
            var value = count()
            while (value != 0 && started.elapsedNow() < WAIT) {
                Thread.sleep(POLL_MILLIS)
                value = count()
            }
            value
        }
    }
}
