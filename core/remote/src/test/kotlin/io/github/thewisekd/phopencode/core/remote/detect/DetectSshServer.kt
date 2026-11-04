package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import org.apache.sshd.common.channel.AbstractChannel
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.session.Session
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.ForwardingFilter

/** Test values only. Nothing here is a real secret. */
internal object DetectSecrets {
    const val USER = "test-user"
    const val PASSWORD = "test-password"
}

internal fun detectPassword() = SshCredential.Password(DetectSecrets.PASSWORD.toCharArray())

/**
 * An embedded MINA server on 127.0.0.1 standing in for the user's server, copied in spirit from the
 * core/remote wiring tests: commands run as `setsid sh -c <command>`, as OpenSSH runs them without
 * a terminal, and `direct-tcpip` reaches local ports.
 *
 * Unlike those tests, each command gets a clean environment whose `HOME` is [home], a fresh
 * temporary folder, and whose `PATH` holds only the system folders. So this machine's own opencode
 * installation is invisible, and fake `opencode` scripts in [home] are what detection finds.
 */
internal class DetectSshServer : AutoCloseable {
    val home: Path = Files.createTempDirectory("phopencode-detect").toRealPath()

    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val groups = ConcurrentLinkedQueue<Long>()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == DetectSecrets.USER && password == DetectSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        commandFactory = CommandFactory { _, command -> ProcessCommand(command) }
        forwardingFilter = object : ForwardingFilter by AcceptAllForwardingFilter.INSTANCE {
            override fun canListen(address: SshdSocketAddress?, session: Session?): Boolean = false
        }
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, DetectSecrets.USER)

    val verifier: HostKeyVerifier
        get() {
            val (type, base64) = PublicKeyEntry.toString(hostKeyPair.public).split(' ')
            val expected = HostKey(type, Base64.decode(base64))
            return HostKeyVerifier { _, key ->
                if (key == expected) HostKeyDecision.Accept else HostKeyDecision.Reject
            }
        }

    /** Added to [environment], for example `XDG_STATE_HOME`. */
    val extraEnvironment: MutableMap<String, String> = ConcurrentHashMap()

    /** The environment every command and every fake server process sees. */
    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.toString(),
        "PATH" to SYSTEM_PATH,
        "SHELL" to "/bin/sh",
        "LC_ALL" to "C.UTF-8",
    ) + extraEnvironment

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        try {
            server.stop(true)
        } finally {
            groups.forEach { group ->
                runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$group").start().waitFor() }
            }
            runCatching { home.deleteRecursively() }
        }
    }

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
            val builder = ProcessBuilder("setsid", "sh", "-c", command).directory(home.toFile())
            builder.environment().apply {
                clear()
                putAll(environment())
            }
            val process = builder.start()
            groups += process.pid()
            val stdinPump = thread(isDaemon = true, name = "detect-test-stdin") {
                runCatching { input.copyTo(process.outputStream) }
                runCatching { process.outputStream.close() }
            }
            val stdoutPump = pump(process.inputStream, output)
            val stderrPump = pump(process.errorStream, error)
            val eof = thread(isDaemon = true, name = "detect-test-eof") {
                stdoutPump.join()
                stderrPump.join()
                runCatching { SEND_EOF.invoke(channel) }
            }
            thread(isDaemon = true, name = "detect-test-wait") {
                val code = process.waitFor()
                eof.join()
                stdinPump.interrupt()
                if (!destroyed.get()) exit.onExit(code)
            }
        }

        private fun pump(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true, name = "detect-test-pump") {
            try {
                from.copyTo(object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        to.write(b, off, len)
                        to.flush()
                    }
                })
            } catch (_: Exception) {
                // The channel is gone; the process keeps running, as under sshd.
            } finally {
                runCatching { from.close() }
            }
        }

        override fun destroy(channel: ChannelSession) {
            destroyed.set(true)
        }
    }

    private companion object {
        const val SYSTEM_PATH = "/usr/bin:/bin"
    }
}

/** `AbstractChannel.sendEof` is protected; sshd sends EOF when the outputs close, MINA only at exit. */
private val SEND_EOF = AbstractChannel::class.java.getDeclaredMethod("sendEof").apply { isAccessible = true }
