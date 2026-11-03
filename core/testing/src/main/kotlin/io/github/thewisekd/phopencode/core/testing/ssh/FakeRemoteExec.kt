package io.github.thewisekd.phopencode.core.testing.ssh

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a scripted command does. It ends by returning [FakeProcess.exit] or [FakeProcess.killedBy]. */
typealias FakeCommand = suspend FakeProcess.() -> ExecEvent.Exited

/**
 * The process a [FakeCommand] plays.
 *
 * @property stdin What the command got. Null means stdin was closed at once.
 */
class FakeProcess internal constructor(val stdin: ByteArray?, private val output: ProducerScope<ExecEvent>) {
    suspend fun stdout(bytes: ByteArray) = output.send(ExecEvent.Stdout(bytes))

    suspend fun stdout(text: String) = stdout(text.encodeToByteArray())

    suspend fun stderr(bytes: ByteArray) = output.send(ExecEvent.Stderr(bytes))

    suspend fun stderr(text: String) = stderr(text.encodeToByteArray())

    fun exit(code: Int): ExecEvent.Exited = ExecEvent.Exited(code, null)

    /** @param signal The name without `SIG`. */
    fun killedBy(signal: String): ExecEvent.Exited = ExecEvent.Exited(null, signal)
}

enum class FakeProcessEnd {
    /** Still running. */
    Running,

    /** Ran to its end. */
    Exited,

    /** Ended by a timeout, a cancellation, the output limit or the end of the connection. */
    Killed,
}

/**
 * One started process.
 *
 * @property stdin A copy, because the caller wipes its own once the command has finished. It may
 *   hold what a test made up as a secret, so this class does not print it.
 */
class FakeExecution internal constructor(val command: String, val stdin: ByteArray?, val timeout: Duration?) {
    @Volatile
    var end: FakeProcessEnd = FakeProcessEnd.Running
        internal set

    override fun toString(): String = "FakeExecution(end=$end)"
}

/**
 * Runs scripted commands. A command with no script behaves like one a shell cannot find: it exits
 * with 127, which is a result and not a failure.
 *
 * The timeout of a request runs on the test's clock, so `runTest` skips it.
 */
class FakeRemoteExec : RemoteExec {
    private val scripts = HashMap<String, FakeCommand>()
    private val failures = ArrayDeque<SshException>()
    private val started = mutableListOf<FakeExecution>()
    private val changes = MutableStateFlow(0)
    private val connectionEnded = CompletableDeferred<Unit>()

    /** Every process so far, oldest first. */
    val executions: List<FakeExecution>
        get() = synchronized(started) { started.toList() }

    /** Sets what [command] does, matched exactly. */
    fun on(command: String, script: FakeCommand) {
        synchronized(scripts) { scripts[command] = script }
    }

    /** The next command fails with [failure] instead of starting. */
    fun failNext(failure: SshException) {
        synchronized(failures) { failures.addLast(failure) }
    }

    fun isRunning(command: String): Boolean =
        executions.any { it.command == command && it.end == FakeProcessEnd.Running }

    suspend fun awaitRunning(command: String) {
        changes.first { isRunning(command) }
    }

    /** Ends the connection: running commands fail with [SshException.Disconnected], and so do later ones. */
    fun disconnect() {
        connectionEnded.complete(Unit)
    }

    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit = ExecEvent.Exited(null, null)
        stream(request).collect { event ->
            when (event) {
                is ExecEvent.Stdout -> stdout.append(event.bytes, maxOutputBytes)
                is ExecEvent.Stderr -> stderr.append(event.bytes, maxOutputBytes)
                is ExecEvent.Exited -> exit = event
            }
        }
        return ExecResult(stdout.toByteArray(), stderr.toByteArray(), exit.exitCode, exit.exitSignal)
    }

    private fun ByteArrayOutputStream.append(bytes: ByteArray, limit: Long) {
        if (size().toLong() + bytes.size > limit) throw SshException.OutputLimitExceeded(limit)
        write(bytes)
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = channelFlow {
        val execution = start(request)
        var end = FakeProcessEnd.Killed
        try {
            val process = FakeProcess(execution.stdin, this)
            val script = synchronized(scripts) { scripts[request.command] } ?: NOT_FOUND
            val exit = untilConnectionEnds { withLimit(request.timeout) { process.script() } }
            end = FakeProcessEnd.Exited
            send(exit)
        } finally {
            execution.end = end
            changes.update { it + 1 }
        }
    }.buffer(Channel.RENDEZVOUS)

    private fun start(request: ExecRequest): FakeExecution {
        if (connectionEnded.isCompleted) throw SshException.Disconnected()
        synchronized(failures) { failures.removeFirstOrNull() }?.let { throw it }
        val execution = FakeExecution(request.command, request.stdin?.copyOf(), request.timeout)
        synchronized(started) { started += execution }
        changes.update { it + 1 }
        return execution
    }

    private suspend fun <T : Any> withLimit(timeout: Duration?, block: suspend () -> T): T =
        if (timeout == null) block() else withTimeoutOrNull(timeout) { block() } ?: throw SshException.Timeout()

    private suspend fun <T> untilConnectionEnds(block: suspend () -> T): T = coroutineScope {
        val watcher = launch {
            connectionEnded.await()
            throw SshException.Disconnected()
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    private companion object {
        const val COMMAND_NOT_FOUND = 127

        val NOT_FOUND: FakeCommand = {
            stderr("command not found")
            exit(COMMAND_NOT_FOUND)
        }
    }
}
