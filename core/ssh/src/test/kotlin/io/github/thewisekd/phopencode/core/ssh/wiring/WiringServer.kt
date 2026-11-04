package io.github.thewisekd.phopencode.core.ssh.wiring

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.ssh.connect.TestKeys
import io.github.thewisekd.phopencode.core.ssh.connect.TestSecrets
import io.github.thewisekd.phopencode.core.ssh.exec.LocalProcesses
import io.github.thewisekd.phopencode.core.ssh.exec.ProcessHeader
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.channel.AbstractChannel
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.ChannelListener
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/**
 * One embedded MINA server on 127.0.0.1 with everything a real `sshd` gives the app: commands run
 * by `sh` like OpenSSH runs them without a terminal, SFTP on the local file system, and
 * `direct-tcpip` forwarding. It counts what the contracts and the smoke test ask about.
 *
 * The SFTP side sees the real file system, so paths are those of [home], a fresh temporary folder;
 * the code under test confines itself to a root inside it.
 */
internal class WiringServer : AutoCloseable {
    val home: Path = Files.createTempDirectory("phopencode-wiring").toRealPath()

    val hostKeyPair: KeyPair = TestKeys.ed25519()

    private val credentials = AtomicInteger()
    private val tunnels = AtomicInteger()
    private val subsystems = AtomicInteger()
    private val openChannels = ConcurrentHashMap.newKeySet<Channel>()

    /** Process groups by the command line the server received. */
    private val groups = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val started = ConcurrentHashMap<String, AtomicInteger>()

    private val sftpListener = object : SftpEventListener {
        override fun initialized(session: org.apache.sshd.server.session.ServerSession, version: Int) {
            subsystems.incrementAndGet()
        }

        override fun destroying(session: org.apache.sshd.server.session.ServerSession) {
            subsystems.decrementAndGet()
        }
    }

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            credentials.incrementAndGet()
            user == TestSecrets.USER && password == TestSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        commandFactory = CommandFactory { _, command -> ProcessCommand(command) }
        fileSystemFactory = NativeFileSystemFactory()
        subsystemFactories = listOf(SftpSubsystemFactory().apply { addSftpEventListener(sftpListener) })
        forwardingFilter = object : ForwardingFilter by AcceptAllForwardingFilter.INSTANCE {
            override fun canListen(address: SshdSocketAddress?, session: Session?): Boolean = false

            // Called while the channel opens, before the client hears it is open.
            override fun canConnect(type: TcpForwardingFilter.Type?, address: SshdSocketAddress?, session: Session?): Boolean {
                tunnels.incrementAndGet()
                return true
            }
        }
        addChannelListener(object : ChannelListener {
            override fun channelOpenSuccess(channel: Channel) {
                openChannels += channel
            }

            override fun channelClosed(channel: Channel, reason: Throwable?) {
                openChannels -= channel
            }
        })
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, TestSecrets.USER)

    val hostKey: HostKey get() = TestKeys.hostKeyOf(hostKeyPair.public)

    fun credentialsReceived(): Int = credentials.get()

    /** Connections that have not logged in, as the server sees them. */
    fun unauthenticatedSessions(): Int = server.activeSessions.count { !it.isAuthenticated }

    fun activeSessions(): Int = server.activeSessions.size

    /** `direct-tcpip` channels the server opened. */
    fun tunnelsOpened(): Int = tunnels.get()

    /** Channels of any kind open on the server now. */
    fun openChannels(): Int = openChannels.size

    /** SFTP subsystems running now. */
    fun openSubsystems(): Int = subsystems.get()

    /** How many processes of [command] the server started, as the app's exec wraps it. */
    fun timesStarted(command: String): Int = started[ProcessHeader.wrap(command)]?.get() ?: 0

    /** Whether a process of [command], or one of its children, still runs. */
    fun isRunning(command: String): Boolean {
        val ids = groups[ProcessHeader.wrap(command)]?.toSet().orEmpty()
        return LocalProcesses.inGroups(ids).isNotEmpty()
    }

    /** Live processes in any group this server started. */
    fun survivors(): List<Long> = LocalProcesses.inGroups(groups.values.flatten().toSet())

    /** Drops every connection from the server's side. */
    fun dropAll() {
        server.activeSessions.toList().forEach { it.close(true) }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        try {
            server.stop(true)
        } finally {
            groups.values.flatten().forEach { group ->
                runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$group").start().waitFor() }
            }
            runCatching { home.deleteRecursively() }
        }
    }

    /**
     * `setsid sh -c <command>`, so the shell leads its own process group as under sshd. End of
     * output goes out once stdout and stderr are closed, then the exit status, or an exit signal
     * for death by SIGKILL/SIGTERM (exit values 137 and 143 as Java reports them).
     */
    private inner class ProcessCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback
        private val destroyed = AtomicBoolean()

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
            val process = ProcessBuilder("setsid", "sh", "-c", command).start()
            // Recorded before anything can see the process run.
            groups.computeIfAbsent(command) { ConcurrentLinkedQueue() } += process.pid()
            started.computeIfAbsent(command) { AtomicInteger() }.incrementAndGet()
            val stdinPump = thread(isDaemon = true, name = "wiring-test-stdin") {
                runCatching { input.copyTo(process.outputStream) }
                runCatching { process.outputStream.close() }
            }
            val stdoutPump = pump(process.inputStream, output)
            val stderrPump = pump(process.errorStream, error)
            val eof = thread(isDaemon = true, name = "wiring-test-eof") {
                stdoutPump.join()
                stderrPump.join()
                runCatching { SEND_EOF.invoke(channel) }
            }
            thread(isDaemon = true, name = "wiring-test-wait") {
                val code = process.waitFor()
                eof.join()
                stdinPump.interrupt()
                if (destroyed.get()) return@thread
                when (code) {
                    137 -> exitSignal(channel, "KILL")
                    143 -> exitSignal(channel, "TERM")
                    else -> exit.onExit(code)
                }
            }
        }

        private fun pump(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true, name = "wiring-test-pump") {
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    to.flush()
                }
            } catch (_: Exception) {
                // The channel is gone; the process keeps running, as under sshd.
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
            destroyed.set(true)
        }
    }
}

/** `AbstractChannel.sendEof` is protected; sshd sends EOF when the outputs close, MINA only at exit. */
private val SEND_EOF = AbstractChannel::class.java.getDeclaredMethod("sendEof").apply { isAccessible = true }
