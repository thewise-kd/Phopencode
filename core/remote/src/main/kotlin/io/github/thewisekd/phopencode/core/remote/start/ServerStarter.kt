package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * @property binary The `opencode` to run: an absolute path, for example from server discovery, or a
 *   bare name looked up on `PATH` (`opencode` is also looked for in `~/.opencode/bin`).
 * @property port The port to listen on; null leaves it to the server and takes it from the ready line.
 * @property timeout How long to wait for the ready line; the server is stopped after that.
 */
data class ServerStartOptions(
    val binary: String = "opencode",
    val port: Int? = null,
    val timeout: Duration = 30.seconds,
) {
    init {
        require(binary.isNotBlank()) { "Binary is blank" }
        require(!binary.startsWith('-')) { "Binary must not start with '-'" }
        require(binary.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Binary contains a control character" }
        require(port == null || port in 1..65535) { "Port is out of range" }
        require(timeout.isPositive() && timeout.isFinite()) { "Timeout must be positive" }
    }
}

/**
 * A server that printed its ready line. [url] is always `http://127.0.0.1:<port>`, reachable only
 * from the server itself, so through the SSH tunnel.
 *
 * @property pid The server's process ID; it leads its own process group.
 */
data class StartedServer(val url: String, val port: Int, val pid: Long)

/**
 * Starts `opencode serve` on the server through [exec], bound to 127.0.0.1, detached from the SSH
 * session, and records it in the [ServerState] file. See [StartScript.SCRIPT] for the shell side.
 *
 * The password reaches the server only as one line on the command's stdin; it is never part of a
 * command line, never written to a file and never in a message. The server reads it from
 * `OPENCODE_SERVER_PASSWORD` and `OPENCODE_PASSWORD`, which opencode 1 and 2 read respectively.
 *
 * The script's output is read as it arrives. Once the server's PID has arrived, every failure stops
 * the server again before the start fails: unexpected or oversized output, a timeout, a lost
 * connection, a state file that could not be written, and cancellation. A cancellation before that
 * moment ends the remote wrapper; a server it launched in that instant keeps running, and server
 * discovery can find it.
 */
class ServerStarter(private val exec: RemoteExec) {
    /**
     * @param password Used, not closed: the caller keeps it for HTTP Basic and closes it.
     * @throws IllegalArgumentException if [password] is empty or holds a line break or a NUL byte.
     * @throws ServerStartException
     * @throws SshException other than a timeout, which is [ServerStartException.Timeout].
     */
    suspend fun start(password: SecretValue, options: ServerStartOptions = ServerStartOptions()): StartedServer {
        val input = passwordLine(password)
        val output = StartOutput()
        try {
            val exit = try {
                launch(options, input, output)
            } finally {
                input.fill(0)
            }
            val (pid, readyLine) = output.outcome(exit)
            val port = StartScript.readyPort(readyLine, options.port) ?: throw ServerStartException.UnexpectedOutput()
            val state = ServerState(port, pid)
            writeState(state)
            return StartedServer(state.url, port, state.pid)
        } catch (failure: Throwable) {
            output.pid?.let { pid -> withContext(NonCancellable) { stop(pid) } }
            throw if (failure is SshException.Timeout) ServerStartException.Timeout() else failure
        }
    }

    /** Runs the start script and hands its stdout to [output] as it arrives. */
    private suspend fun launch(options: ServerStartOptions, input: ByteArray, output: StartOutput): ExecEvent.Exited {
        var exit = ExecEvent.Exited(null, null)
        val request = ExecRequest(StartScript.startCommand(options), input, options.timeout + BACKSTOP)
        exec.stream(request).collect { event ->
            when (event) {
                is ExecEvent.Stdout -> output.feed(event.bytes)
                is ExecEvent.Stderr -> Unit
                is ExecEvent.Exited -> exit = event
            }
        }
        // A flow may end quietly when its collector is cancelled; that is still a cancellation.
        currentCoroutineContext().ensureActive()
        return exit
    }

    /** One line: the password and `\n`. The caller wipes it. */
    private fun passwordLine(password: SecretValue): ByteArray {
        val bytes = password.revealBytes()
        try {
            require(bytes.isNotEmpty()) { "Password is empty" }
            require(bytes.none { it == NEWLINE || it == CARRIAGE_RETURN || it == NUL }) {
                "Password contains a line break or a NUL byte"
            }
            return bytes.copyOf(bytes.size + 1).also { it[bytes.size] = NEWLINE }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun writeState(state: ServerState) {
        val written = try {
            exec.run(ExecRequest(StartScript.WRITE_STATE_COMMAND, state.encode(), STEP_TIMEOUT), SMALL_OUTPUT).succeeded
        } catch (_: SshException.Timeout) {
            false
        } catch (_: SshException.OutputLimitExceeded) {
            false
        }
        if (!written) throw ServerStartException.StateNotWritten()
    }

    /** Best effort: the start already failed, and that failure is what the caller hears about. */
    private suspend fun stop(pid: Long) {
        try {
            exec.run(ExecRequest(StartScript.stopCommand(pid), timeout = STEP_TIMEOUT), SMALL_OUTPUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nothing more can be done over this connection.
        }
    }

    /**
     * Reads the script's stdout line by line as it arrives; see [StartScript.SCRIPT] for the
     * protocol. [pid] is set as soon as its line is complete. More than [MAX_OUTPUT_BYTES] in all
     * is unexpected output, which the script never prints itself.
     */
    private class StartOutput {
        @Volatile
        var pid: Long? = null
            private set

        private val partial = ByteArrayOutputStream()
        private var total = 0L
        private var pidLines = 0
        private var status: String? = null
        private var ready: String? = null
        private var outcomes = 0

        /**
         * Counts byte by byte, so every line that ends within the limit is read, the PID line too,
         * before oversized output fails, even when both arrive in one chunk.
         */
        fun feed(bytes: ByteArray) {
            for (byte in bytes) {
                total++
                if (total > MAX_OUTPUT_BYTES) throw ServerStartException.UnexpectedOutput()
                if (byte == NEWLINE) {
                    line(partial.toByteArray().decodeToString())
                    partial.reset()
                } else {
                    partial.write(byte.toInt())
                }
            }
        }

        private fun line(text: String) {
            when {
                text.startsWith(PID_PREFIX) -> {
                    pidLines++
                    val value = text.removePrefix(PID_PREFIX).takeIf { PID.matches(it) }?.toLongOrNull()
                    if (pid == null && value != null && value > 1) pid = value
                }
                text.startsWith(READY_PREFIX) -> {
                    ready = text.removePrefix(READY_PREFIX)
                    outcomes++
                }
                text.startsWith(STATUS_PREFIX) -> {
                    status = text.removePrefix(STATUS_PREFIX)
                    outcomes++
                }
            }
        }

        /** The PID and the ready line, or the failure the output reports. */
        fun outcome(exit: ExecEvent.Exited): Pair<Long, String> {
            // A line without its newline may be cut short, a port included; it is never used.
            if (partial.size() > 0) throw ServerStartException.UnexpectedOutput()
            val validPid = pidLines == 0 || (pidLines == 1 && pid != null)
            if (exit.exitCode != 0 || outcomes != 1 || !validPid) throw ServerStartException.UnexpectedOutput()
            when (status) {
                null -> Unit
                "missing" -> throw ServerStartException.BinaryMissing()
                "state-dir" -> throw ServerStartException.StateNotWritten()
                "port-in-use" -> throw ServerStartException.PortInUse()
                "exited" -> throw ServerStartException.Exited()
                "timeout" -> throw ServerStartException.Timeout()
                else -> throw ServerStartException.UnexpectedOutput()
            }
            val known = pid ?: throw ServerStartException.UnexpectedOutput()
            return known to checkNotNull(ready)
        }
    }

    internal companion object {
        const val PID_PREFIX = "phopencode-start-pid="
        const val READY_PREFIX = "phopencode-start-ready="
        const val STATUS_PREFIX = "phopencode-start="
        val PID = Regex("[1-9][0-9]{0,18}")

        const val NEWLINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
        const val NUL: Byte = 0

        /** The script enforces the start timeout; this only catches a script that hangs. */
        val BACKSTOP: Duration = 15.seconds
        val STEP_TIMEOUT: Duration = 15.seconds

        /** All the start script may print; it prints a few hundred bytes. */
        const val MAX_OUTPUT_BYTES = 64L * 1024
        const val SMALL_OUTPUT = 4L * 1024
    }
}
