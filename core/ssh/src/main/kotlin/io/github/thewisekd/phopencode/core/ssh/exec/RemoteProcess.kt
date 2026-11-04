package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal

/**
 * One command on its own session channel. The blocking calls here are sshj's; callers run them
 * on an IO thread they may interrupt.
 *
 * Closing ends the remote process if its channel is still open: an SSH `signal` request, which
 * OpenSSH honours from 7.9 on, then `kill` of the process group on a second channel, then the
 * channel itself. Once the server has closed the channel, the process has ended (or, for a
 * command that put work in the background on purpose, let go of it), and nothing is killed.
 *
 * If the start was cut short after the exec request went out, the process may run although its
 * ID was not read yet; closing then waits up to [HEADER_WAIT_MILLIS] for the ID before the kill.
 * Without it, only the signal request and closing the channel remain, which a process that
 * neither honours the signal nor writes output survives.
 */
internal class RemoteProcess private constructor(
    private val session: SshjSession,
    private val channel: Session,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    @Volatile
    private var command: Session.Command? = null

    /** Set before the exec request is sent: an interrupted exec may still have reached the server. */
    @Volatile
    private var execSent = false

    private val headerLock = Any()
    private val parser = ProcessHeader.Parser()

    @Volatile
    private var header: ProcessHeader.Header? = null

    private var leftover = ByteArray(0)

    /** True once [close] has begun, whoever called it. */
    val isClosed: Boolean get() = closed.get()

    /** Starts [commandLine]. Then [readPidLine], while stderr is already being read. */
    fun start(commandLine: String, afterExecSent: () -> Unit = {}) {
        execSent = true
        command = channel.exec(ProcessHeader.wrap(commandLine))
        afterExecSent()
    }

    /**
     * Reads the line that names the process from the start of stdout. Whatever the login shell
     * writes to stderr first must be read meanwhile, or a full channel window stops it before it
     * ever prints this line.
     */
    fun readPidLine() {
        leftover = readHeader().leftover
    }

    /**
     * Reads the header once. Serialised, and the parser keeps its place, so that a read the start
     * left unfinished can be completed by [close].
     */
    private fun readHeader(): ProcessHeader.Header = synchronized(headerLock) {
        header ?: run {
            val input = channel.inputStream
            var decided: ProcessHeader.Header? = null
            while (decided == null) decided = parser.feed(input.read())
            decided.also { header = it }
        }
    }

    val stdout: InputStream get() = started().inputStream

    val stderr: InputStream get() = started().errorStream

    /** Output read while looking for the header; it comes before anything from [stdout]. */
    fun takeLeftover(): ByteArray = leftover.also { leftover = ByteArray(0) }

    /**
     * Writes [stdin], if any, then closes the process's input. A process that has stopped reading
     * or has ended makes the write fail; that is not an error of the command.
     */
    fun writeStdin(stdin: ByteArray?) {
        val output = started().outputStream
        try {
            if (stdin != null && stdin.isNotEmpty()) {
                output.write(stdin)
                output.flush()
            }
        } catch (_: IOException) {
            // See above.
        } finally {
            runCatching { output.close() }
        }
    }

    /**
     * True once the server reported the process's end or closed the channel. A server that does
     * neither keeps this false until the channel ends some other way.
     */
    fun hasEnded(): Boolean {
        val started = command ?: return !channel.isOpen
        return started.exitStatus != null || started.exitSignal != null || !channel.isOpen
    }

    /** Waits until the server closes the channel; then the exit status, if any, has arrived. */
    fun awaitExit(): Exit {
        val started = started()
        started.join()
        return Exit(started.exitStatus, started.exitSignal?.name)
    }

    class Exit(val code: Int?, val signal: String?)

    /**
     * Idempotent and bounded. The work runs on its own thread for at most [CLOSE_BUDGET_MILLIS];
     * a connection that stops answering then gets the thread interrupted, which breaks sshj's
     * waits, so the thread also ends within a bounded time instead of waiting for the session.
     *
     * The channel's close is sent either way. It is not forgotten locally without the server's
     * answer: sshj ends the whole connection when a message arrives for a channel it does not know.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        session.untrack(this)
        val worker = thread(isDaemon = true, name = "phopencode-ssh-exec-close") { shutDown() }
        if (!join(worker, CLOSE_BUDGET_MILLIS)) return
        // An sshj wait may swallow one interrupt, so it is repeated a few times.
        repeat(INTERRUPTS) {
            if (!worker.isAlive) return
            worker.interrupt()
            if (!join(worker, INTERRUPT_GRACE_MILLIS)) return
        }
    }

    private fun shutDown() {
        try {
            if (channel.isOpen) {
                (channel as? Session.Command)?.let { runCatching { it.signal(Signal.KILL) } }
                val pid = header?.pid ?: if (execSent) awaitPid() else null
                if (pid != null) killGroup(pid)
            }
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Finishes reading the header, for at most [HEADER_WAIT_MILLIS]. */
    private fun awaitPid(): Long? {
        val reader = thread(isDaemon = true, name = "phopencode-ssh-exec-header") { runCatching { readHeader() } }
        try {
            reader.join(HEADER_WAIT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (reader.isAlive) reader.interrupt()
        }
        return header?.pid
    }

    private fun killGroup(pid: Long) {
        // Not tracked: this channel must work while the session is closing, and is closed below.
        runCatching {
            val killer = session.client.startSession()
            try {
                killer.exec(ProcessHeader.killCommand(pid)).join(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } finally {
                runCatching { killer.close() }
            }
        }
    }

    private fun started(): Session.Command = checkNotNull(command) { "Not started" }

    companion object {
        private const val CLOSE_BUDGET_MILLIS = 5_000L
        private const val HEADER_WAIT_MILLIS = 2_000L
        private const val KILL_WAIT_MILLIS = 2_000L
        private const val INTERRUPTS = 3
        private const val INTERRUPT_GRACE_MILLIS = 300L

        /** False if the calling thread was interrupted; it keeps its interrupt then. */
        private fun join(thread: Thread, millis: Long): Boolean = try {
            thread.join(millis)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        /**
         * Opens a channel and hands the process to [opened] before anything else can fail, so the
         * caller can always close it.
         *
         * @throws SshException.Disconnected when the session is closed.
         */
        fun open(session: SshjSession, opened: (RemoteProcess) -> Unit): RemoteProcess {
            session.requireConnected()
            val process = RemoteProcess(session, session.client.startSession())
            opened(process)
            if (!session.track(process)) throw SshException.Disconnected()
            return process
        }
    }
}
