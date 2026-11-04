package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.NoSessionFeatures
import io.github.thewisekd.phopencode.core.ssh.connect.SessionFeatures
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import io.github.thewisekd.phopencode.core.ssh.connect.TestKeys
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import io.github.thewisekd.phopencode.core.ssh.connect.password
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.channel.AbstractChannel
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory

/**
 * How the test server reports the end of a process.
 *
 * - [Real]: like OpenSSH: an exit status, or for death by SIGKILL/SIGTERM (exit value 137/143 as
 *   Java reports it) an exit signal.
 * - [Nothing]: closes the channel without either.
 */
internal enum class ExitReport { Real, Nothing }

/**
 * An embedded MINA server on 127.0.0.1 that runs each command like OpenSSH does without a terminal:
 * `sh -c <command>` in a new session (setsid), so the shell leads its own process group. Closing the
 * channel closes the pipes and nothing else; the process keeps running, as it would under sshd.
 * Like sshd, it sends end of output once the process has closed both stdout and stderr, which can be
 * before the process ends. [close] kills every process group it started.
 *
 * With [fixedStdout], no process runs: the channel prints those bytes and exits with 0, as a server
 * that does not run a POSIX shell might.
 */
internal class ExecTestServer(
    private val exitReport: ExitReport = ExitReport.Real,
    private val fixedStdout: ByteArray? = null,
    /** Zero bytes the "login shell" writes to stderr before it runs the command, like noisy rc files. */
    private val startupStderrBytes: Int = 0,
) : AutoCloseable {
    private val started = AtomicInteger()
    private val ended = AtomicInteger()
    private val groups = ConcurrentLinkedQueue<Long>()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(TestKeys.ed25519())
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        commandFactory = CommandFactory { _, command -> ProcessCommand(command) }
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, TestSecrets.USER)

    /** Channels whose command started and that are not closed yet. */
    fun openChannels(): Int = started.get() - ended.get()

    fun commandsStarted(): Int = started.get()

    fun dropAll() {
        server.activeSessions.toList().forEach { it.close(true) }
    }

    /** Processes still running in a process group this server started. */
    fun survivors(): List<Long> = LocalProcesses.inGroups(groups.toSet())

    override fun close() {
        try {
            server.stop(true)
        } finally {
            groups.forEach { group ->
                runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$group").start().waitFor() }
            }
        }
    }

    private inner class ProcessCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback
        private val destroyed = java.util.concurrent.atomic.AtomicBoolean()

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) {
            error = err
        }

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            started.incrementAndGet()
            val fixed = fixedStdout
            if (fixed != null) {
                thread(isDaemon = true, name = "exec-test-fixed") {
                    runCatching {
                        output.write(fixed)
                        output.flush()
                    }
                    exit.onExit(0)
                }
                return
            }
            val startup = if (startupStderrBytes > 0) "head -c $startupStderrBytes /dev/zero >&2; " else ""
            val process = ProcessBuilder("setsid", "sh", "-c", startup + command).start()
            // setsid does not fork here (Java's child leads no group), so the pid names the group.
            groups += process.pid()
            val stdinPump = thread(isDaemon = true, name = "exec-test-stdin") {
                runCatching { input.copyTo(process.outputStream) }
                runCatching { process.outputStream.close() }
            }
            val stdoutPump = pump(process.inputStream, output)
            val stderrPump = pump(process.errorStream, error)
            val eof = thread(isDaemon = true, name = "exec-test-eof") {
                stdoutPump.join()
                stderrPump.join()
                runCatching { SEND_EOF.invoke(channel) }
            }
            thread(isDaemon = true, name = "exec-test-wait") {
                val code = process.waitFor()
                // EOF, then the exit report and close, in that order, as sshd does. An EOF after the
                // close would be a protocol error that ends the whole connection.
                eof.join()
                stdinPump.interrupt()
                if (destroyed.get()) return@thread
                when (exitReport) {
                    ExitReport.Real -> when (code) {
                        137 -> exitSignal(channel, "KILL")
                        143 -> exitSignal(channel, "TERM")
                        else -> exit.onExit(code)
                    }
                    ExitReport.Nothing -> channel.close(false)
                }
            }
        }

        private fun pump(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true, name = "exec-test-pump") {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    to.flush()
                }
            } catch (_: Exception) {
                // The channel is gone; the process keeps running and gets a broken pipe, as under sshd.
            } finally {
                runCatching { from.close() }
            }
        }

        private fun exitSignal(channel: ChannelSession, signal: String) {
            runCatching {
                val buffer = channel.session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST)
                buffer.putUInt(channel.recipient)
                buffer.putString("exit-signal")
                buffer.putBoolean(false)
                buffer.putString(signal)
                buffer.putBoolean(false)
                buffer.putString("")
                buffer.putString("")
                channel.writePacket(buffer)
            }
            channel.close(false)
        }

        override fun destroy(channel: ChannelSession) {
            if (destroyed.compareAndSet(false, true)) ended.incrementAndGet()
        }
    }
}

/** `AbstractChannel.sendEof` is protected; sshd sends EOF when the outputs close, MINA only at exit. */
private val SEND_EOF = AbstractChannel::class.java.getDeclaredMethod("sendEof").apply { isAccessible = true }

/** Connects with the exec feature under test plugged in. */
internal suspend fun ExecTestServer.connect(
    options: SshConnectOptions = SshConnectOptions(keepAliveInterval = null),
    via: SshTarget = target,
    exec: (SshjSession) -> RemoteExec = ExecFeature::exec,
): SshSession {
    val features = object : SessionFeatures by NoSessionFeatures {
        override fun exec(session: SshjSession) = exec(session)
    }
    return SshjConnector(features).connect(via, password(), HostKeyVerifier { _, _ -> HostKeyDecision.Accept }, options)
}

/**
 * A TCP relay to [to] on 127.0.0.1. After [freeze], nothing from the server reaches the client any
 * more, while the connection stays open: a server that stops answering.
 */
internal class FreezableProxy(private val to: SshTarget) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentLinkedQueue<Socket>()

    @Volatile
    private var frozen = false

    val target: SshTarget get() = SshTarget("127.0.0.1", listener.localPort, to.username)

    init {
        thread(isDaemon = true, name = "exec-test-proxy") {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                val server = Socket(to.host, to.port)
                sockets += client
                sockets += server
                relay(client, server, fromServer = false)
                relay(server, client, fromServer = true)
            }
        }
    }

    fun freeze() {
        frozen = true
    }

    private fun relay(from: Socket, to: Socket, fromServer: Boolean) = thread(isDaemon = true, name = "exec-test-relay") {
        val buffer = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val count = from.getInputStream().read(buffer)
                if (count < 0) break
                // Frozen: what the server sends is swallowed, as if it never came.
                if (fromServer && frozen) continue
                to.getOutputStream().write(buffer, 0, count)
                to.getOutputStream().flush()
            }
        }
        runCatching { to.shutdownOutput() }
    }

    override fun close() {
        runCatching { listener.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}

/** Processes on this machine, which is the test server's "remote" side. */
internal object LocalProcesses {
    /** True while [pid] runs; a zombie counts as ended. */
    fun alive(pid: Long): Boolean {
        val stat = Path.of("/proc/$pid/stat")
        val text = runCatching { Files.readString(stat) }.getOrNull() ?: return false
        // The state follows the command name, which is in parentheses and may contain spaces.
        val state = text.substringAfterLast(')').trim().firstOrNull()
        return state != null && state != 'Z' && state != 'X'
    }

    /** Live processes whose process group is one of [groups], read from /proc. */
    fun inGroups(groups: Set<Long>): List<Long> {
        if (groups.isEmpty()) return emptyList()
        val entries = Files.list(Path.of("/proc")).use { it.toList() }
        return entries.mapNotNull { it.fileName.toString().toLongOrNull() }.filter { pid ->
            val text = runCatching { Files.readString(Path.of("/proc/$pid/stat")) }.getOrNull() ?: return@filter false
            // After the name: state, parent, process group.
            val fields = text.substringAfterLast(')').trim().split(' ')
            val state = fields.getOrNull(0)?.firstOrNull()
            state != 'Z' && state != 'X' && fields.getOrNull(2)?.toLongOrNull() in groups
        }
    }

    fun readPid(file: Path): Long? =
        runCatching { Files.readString(file).trim().toLong() }.getOrNull()
}
