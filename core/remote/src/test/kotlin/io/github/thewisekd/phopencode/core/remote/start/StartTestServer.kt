package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory

/**
 * An embedded MINA server on 127.0.0.1 whose commands run as `setsid sh -c <command>`, the way
 * OpenSSH runs a command without a terminal, in a clean environment: `HOME` is [home], a fresh
 * temporary folder, and `PATH` is [bin] followed by the system folders. So the code under test
 * sees a fake `opencode` there and never the real one.
 */
internal class StartTestServer : AutoCloseable {
    val home: Path = Files.createTempDirectory("phopencode-start").toRealPath()
    val bin: Path = Files.createDirectories(home.resolve("bin"))

    private val user = "start-user"
    private val loginWord = "login-" + System.nanoTime()
    private val groups = ConcurrentLinkedQueue<Long>()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair())
        setPasswordAuthenticator { name, given, _ -> name == user && given == loginWord }
        keyboardInteractiveAuthenticator = null
        commandFactory = CommandFactory { _, command -> ProcessCommand(command) }
        start()
    }

    val target: SshTarget get() = SshTarget("127.0.0.1", server.port, user)

    fun credential(): SshCredential = SshCredential.Password(loginWord.toCharArray())

    @Volatile
    private var path: String = "$bin:/usr/local/bin:/usr/bin:/bin"

    /**
     * Commands from now on see every tool of /usr/bin except `setsid`, as on a server without
     * util-linux. The tools are symlinks in a folder of [home].
     */
    fun hideSetsid() {
        val tools = Files.createDirectories(home.resolve("tools"))
        Files.list(Path.of("/usr/bin")).use { entries ->
            entries.toList().filter { it.fileName.toString() != "setsid" }.forEach { tool ->
                Files.createSymbolicLink(tools.resolve(tool.fileName.toString()), tool)
            }
        }
        path = "$bin:$tools"
    }

    /** Commands from now on get [value] as `PATH`. They start in [home], as under sshd. */
    fun usePath(value: String) {
        path = value
    }

    override fun close() {
        try {
            server.stop(true)
        } finally {
            groups.forEach { kill(it) }
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
                put("HOME", home.toString())
                put("PATH", path)
                put("LANG", "C.UTF-8")
            }
            val process = builder.start()
            groups += process.pid()
            thread(isDaemon = true, name = "start-test-stdin") {
                runCatching { input.copyTo(process.outputStream) }
                runCatching { process.outputStream.close() }
            }
            val out = pump(process.inputStream, output)
            val err = pump(process.errorStream, error)
            thread(isDaemon = true, name = "start-test-wait") {
                val code = process.waitFor()
                out.join()
                err.join()
                exit.onExit(code)
            }
        }

        private fun pump(from: InputStream, to: OutputStream): Thread = thread(isDaemon = true, name = "start-test-pump") {
            runCatching {
                from.use { source ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        to.write(buffer, 0, count)
                        to.flush()
                    }
                }
            }
        }

        override fun destroy(channel: ChannelSession) = Unit
    }

    companion object {
        /** Kills the process group led by [pid], then the process itself. */
        fun kill(pid: Long) {
            runCatching { ProcessBuilder("kill", "-s", "KILL", "--", "-$pid").start().waitFor() }
            runCatching { ProcessBuilder("kill", "-s", "KILL", "$pid").start().waitFor() }
        }
    }
}

/** Processes on this machine, read from /proc. */
internal object Procs {
    fun all(): List<Long> = Files.list(Path.of("/proc")).use { list ->
        list.toList().mapNotNull { it.fileName.toString().toLongOrNull() }
    }

    /** The raw command line, NUL-separated, or null when the process is gone or unreadable. */
    fun cmdline(pid: Long): ByteArray? = runCatching { Files.readAllBytes(Path.of("/proc/$pid/cmdline")) }.getOrNull()

    fun argv(pid: Long): List<String>? = cmdline(pid)?.decodeToString()?.split('\u0000')?.dropLastWhile { it.isEmpty() }

    /** Whether [pid] is alive and not a zombie. */
    fun alive(pid: Long): Boolean {
        val stat = runCatching { Files.readString(Path.of("/proc/$pid/stat")) }.getOrNull() ?: return false
        val state = stat.substringAfterLast(')').trim().firstOrNull()
        return state != null && state != 'Z' && state != 'X'
    }

    /** Live processes whose command line mentions [text]. */
    fun mentioning(text: String): List<Long> {
        val needle = text.encodeToByteArray()
        return all().filter { pid -> cmdline(pid)?.let { contains(it, needle) } == true && alive(pid) }
    }

    fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) if (haystack[start + index] != needle[index]) continue@outer
            return true
        }
        return false
    }
}
