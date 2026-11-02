package io.github.thewisekd.phopencode.core.ssh.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow

/**
 * Runs commands on the server through the login shell.
 *
 * A timeout, a cancellation and [SshException.OutputLimitExceeded] all end the remote process
 * itself; closing the channel is not enough, because a process without a terminal survives that.
 * A non-zero exit is a result, not a failure.
 */
interface RemoteExec {
    /**
     * Runs a command to its end and collects the output.
     *
     * @param maxOutputBytes Limit for stdout and for stderr, each. A phone cannot hold what a
     *   server can print.
     * @throws SshException.Timeout
     * @throws SshException.OutputLimitExceeded
     * @throws SshException.Disconnected
     */
    suspend fun run(request: ExecRequest, maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES): ExecResult

    /**
     * Runs a command and delivers output as it arrives, for long commands and for reading a line
     * from a process that keeps running. Each collection starts the process once. The last event
     * is [ExecEvent.Exited], unless collection stops first, which ends the process.
     *
     * @throws SshException.Timeout from collection.
     * @throws SshException.Disconnected from collection.
     */
    fun stream(request: ExecRequest): Flow<ExecEvent>

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Long = 16L * 1024 * 1024
    }
}

/**
 * One command. This is a plain class because its fields can hold secrets: it prints nothing, and
 * has no generated `copy` or component functions.
 *
 * @property command One shell command line. It is visible in the server's process list, so a secret
 *   never goes here; it goes in [stdin]. Every variable part passes through
 *   [ShellQuote][io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote].
 * @property stdin Written to the process, then closed. Null closes stdin at once, so a command that
 *   prompts sees end of input and cannot hang. Not copied: the caller wipes it once the command has
 *   finished, and implementations never turn it into a `String`.
 * @property timeout Limit for the whole command. Null means no limit, for commands that stay running.
 */
class ExecRequest(
    val command: String,
    val stdin: ByteArray? = null,
    val timeout: Duration? = DEFAULT_TIMEOUT,
) {
    init {
        require(command.isNotBlank()) { "Command is blank" }
        require('\u0000' !in command) { "Command contains a NUL byte" }
        require(timeout == null || timeout.isPositive()) { "Timeout must be positive" }
    }

    override fun toString(): String = "ExecRequest(***)"

    companion object {
        val DEFAULT_TIMEOUT: Duration = 1.minutes
    }
}

/**
 * What a finished command left. Output is bytes because neither file names nor file content have to
 * be valid UTF-8. It can hold anything the command read, so it does not print.
 *
 * @property exitCode Null when the process died from a signal or the server reported nothing.
 * @property exitSignal The signal's name without `SIG`, for example `KILL`.
 */
class ExecResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int?,
    val exitSignal: String?,
) {
    val succeeded: Boolean get() = exitCode == 0

    override fun toString(): String =
        "ExecResult(exitCode=$exitCode, exitSignal=$exitSignal, stdout=${stdout.size} bytes, stderr=${stderr.size} bytes)"
}

sealed interface ExecEvent {
    /** A chunk of output. Chunks can split a line or a multi-byte character. */
    class Stdout(val bytes: ByteArray) : ExecEvent {
        override fun toString(): String = "Stdout(${bytes.size} bytes)"
    }

    /** See [Stdout]. */
    class Stderr(val bytes: ByteArray) : ExecEvent {
        override fun toString(): String = "Stderr(${bytes.size} bytes)"
    }

    /** See [ExecResult] for the fields. */
    data class Exited(val exitCode: Int?, val exitSignal: String?) : ExecEvent
}
