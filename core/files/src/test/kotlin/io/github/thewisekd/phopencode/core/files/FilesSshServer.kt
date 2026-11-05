package io.github.thewisekd.phopencode.core.files

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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import org.apache.sshd.common.channel.AbstractChannel
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/** Test values only. Nothing here is a real secret. */
internal object FilesSecrets {
    const val USER = "test-user"
    const val PASSWORD = "test-password"
}

internal fun filesPassword() = SshCredential.Password(FilesSecrets.PASSWORD.toCharArray())

/**
 * An embedded MINA server on 127.0.0.1 standing in for the user's server: SFTP on this machine's
 * file system, and commands run as `setsid sh -c <command>` in [home], a fresh temporary folder,
 * with `PATH` holding the system folders only. Tests work below [home] and nowhere else.
 */
internal class FilesSshServer : AutoCloseable {
    val home: Path = Files.createTempDirectory("phopencode-files").toRealPath()

    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val groups = ConcurrentLinkedQueue<Long>()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == FilesSecrets.USER && password == FilesSecrets.PASSWORD
        }
        keyboardInteractiveAuthenticator = null
        commandFactory = CommandFactory { _, command -> ProcessCommand(command) }
        fileSystemFactory = NativeFileSystemFactory()
        subsystemFactories = listOf(SftpSubsystemFactory())
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, FilesSecrets.USER)

    val verifier: HostKeyVerifier
        get() {
            val (type, base64) = PublicKeyEntry.toString(hostKeyPair.public).split(' ')
            val expected = HostKey(type, Base64.decode(base64))
            return HostKeyVerifier { _, key -> if (key == expected) HostKeyDecision.Accept else HostKeyDecision.Reject }
        }

    override fun close() {
        try {
            server.stop(true)
        } finally {
            // Only the test's own process groups, should a command outlive its channel.
            groups.forEach { group ->
                runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$group").start().waitFor() }
            }
            runCatching { home.toFile().deleteRecursively() }
        }
    }

    private inner class ProcessCommand(private val command: String) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private lateinit var exit: ExitCallback

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
                putAll(mapOf("HOME" to home.toString(), "PATH" to "/usr/bin:/bin", "LC_ALL" to "C.UTF-8"))
            }
            val process = builder.start()
            groups += process.pid()
            thread(isDaemon = true, name = "files-test-stdin") {
                runCatching { input.copyTo(process.outputStream) }
                runCatching { process.outputStream.close() }
            }
            val stdoutPump = pump(process.inputStream, output)
            val stderrPump = pump(process.errorStream, error)
            thread(isDaemon = true, name = "files-test-wait") {
                val code = process.waitFor()
                stdoutPump.join()
                stderrPump.join()
                runCatching { SEND_EOF.invoke(channel) }
                exit.onExit(code)
            }
        }

        private fun pump(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true, name = "files-test-pump") {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = from.read(buffer)
                    if (count < 0) break
                    to.write(buffer, 0, count)
                    to.flush()
                }
            } catch (_: Exception) {
                // The channel is gone.
            } finally {
                runCatching { from.close() }
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}

/** `AbstractChannel.sendEof` is protected; sshd sends EOF when the outputs close, MINA only at exit. */
private val SEND_EOF = AbstractChannel::class.java.getDeclaredMethod("sendEof").apply { isAccessible = true }
