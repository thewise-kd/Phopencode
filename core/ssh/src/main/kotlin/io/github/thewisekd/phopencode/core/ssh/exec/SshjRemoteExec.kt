package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.connect.RedactedFailure
import io.github.thewisekd.phopencode.core.ssh.connect.SshjSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What other packages plug into a session for [RemoteExec]. */
internal object ExecFeature {
    fun exec(session: SshjSession): RemoteExec = SshjRemoteExec(session)
}

/**
 * [RemoteExec] over sshj: one session channel per command, without a terminal, so stdout and stderr
 * stay apart and binary input passes unchanged. See [ProcessHeader] for how a command is wrapped so
 * that it can be ended, and [RemoteProcess] for how.
 *
 * Blocking sshj calls run on [Dispatchers.IO] and are interrupted on cancellation. Output is read in
 * chunks and handed on before the next read, so nothing is buffered beyond sshj's channel window,
 * the [run] limit and the [stream] buffer. Nothing here logs; commands, input and output can all
 * hold secrets.
 */
internal class SshjRemoteExec(
    private val session: SshjSession,
    /** For tests: runs on the IO thread once the exec request is sent, before the PID is read. */
    private val afterExecSent: () -> Unit = {},
) : RemoteExec {
    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
        require(maxOutputBytes >= 0) { "Output limit must not be negative" }
        val stdout = BoundedBuffer(maxOutputBytes)
        val stderr = BoundedBuffer(maxOutputBytes)
        val exit = execute(request) { output, bytes, count ->
            when (output) {
                Output.Stdout -> stdout.append(bytes, count)
                Output.Stderr -> stderr.append(bytes, count)
            }
        }
        return ExecResult(stdout.toByteArray(), stderr.toByteArray(), exit.exitCode, exit.exitSignal)
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = channelFlow {
        val exit = execute(request) { output, bytes, count ->
            val chunk = bytes.copyOf(count)
            send(
                when (output) {
                    Output.Stdout -> ExecEvent.Stdout(chunk)
                    Output.Stderr -> ExecEvent.Stderr(chunk)
                },
            )
        }
        send(exit)
    }.buffer(STREAM_BUFFER)

    private enum class Output { Stdout, Stderr }

    /** Receives a chunk of output; [bytes] is reused after it returns. */
    private fun interface Sink {
        suspend fun accept(output: Output, bytes: ByteArray, count: Int)
    }

    private suspend fun execute(request: ExecRequest, sink: Sink): ExecEvent.Exited {
        val opened = AtomicReference<RemoteProcess?>()
        try {
            // On IO, the timeout and the polling delays measure real time, also when the caller
            // runs on a test dispatcher whose virtual clock would fire the timeout at once.
            val exit = withContext(Dispatchers.IO) {
                timed(request.timeout) {
                    val process = blocking { RemoteProcess.open(session, opened::set) }
                    drive(process, request, sink)
                }
            }
            // The session closed it under us; whatever the server said after that is not the
            // command's own end.
            if (opened.get()?.isClosed == true) throw SshException.Disconnected()
            if (exit.exitCode == null && exit.exitSignal == null && !session.connected.value) {
                throw SshException.Disconnected()
            }
            return exit
        } catch (failure: IOException) {
            if (failure is SshException) throw failure
            throw SshException.Disconnected(RedactedFailure.of(failure))
        } finally {
            opened.get()?.let { process -> withContext(NonCancellable + Dispatchers.IO) { process.close() } }
        }
    }

    private suspend fun drive(process: RemoteProcess, request: ExecRequest, sink: Sink): ExecEvent.Exited =
        coroutineScope {
            blocking { process.start(request.command, afterExecSent) }
            // stderr and stdin flow from the start; only stdout waits for the PID line.
            val stderrReader = launch { pump(process.stderr, Output.Stderr, sink) }
            val writer = launch { blocking { process.writeStdin(request.stdin) } }
            blocking { process.readPidLine() }
            val leftover = process.takeLeftover()
            val stdoutReader = launch {
                if (leftover.isNotEmpty()) sink.accept(Output.Stdout, leftover, leftover.size)
                pump(process.stdout, Output.Stdout, sink)
            }
            val readers = listOf(stdoutReader, stderrReader)
            readers.forEach { it.join() }
            // End of output is not the end of the process: one that closed stdout and stderr may
            // still read its input, so the write goes on until the process has ended. It must not
            // go on past that: a write blocked on a process that stopped reading holds the stream
            // that sshj's own thread closes when the server closes the channel, which would stall
            // that thread until sshj's wait for window space runs out. The exit report comes
            // before the server's close, so the write is ended as soon as it arrives.
            while (writer.isActive && !process.hasEnded()) delay(EXIT_POLL)
            writer.cancelAndJoin()
            val exit = blocking { process.awaitExit() }
            ExecEvent.Exited(exit.code, exit.signal)
        }

    private suspend fun pump(input: InputStream, output: Output, sink: Sink) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val count = blocking { input.read(buffer) }
            if (count < 0) return
            if (count > 0) sink.accept(output, buffer, count)
        }
    }

    /**
     * Runs a blocking sshj call on an IO thread and interrupts it on cancellation. An interrupted
     * call fails with an IOException; once cancelled, that is reported as the cancellation, so a
     * timeout stays a timeout.
     */
    private suspend fun <T> blocking(block: () -> T): T =
        try {
            runInterruptible(Dispatchers.IO, block)
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        }

    private suspend fun <T : Any> timed(timeout: Duration?, block: suspend CoroutineScope.() -> T): T =
        if (timeout == null) {
            coroutineScope(block)
        } else {
            withTimeoutOrNull(timeout, block) ?: throw SshException.Timeout()
        }

    /** Collects up to [limit] bytes and fails, rather than grows, past it. */
    private class BoundedBuffer(private val limit: Long) {
        private val bytes = ByteArrayOutputStream()

        fun append(chunk: ByteArray, count: Int) {
            if (bytes.size().toLong() + count > limit) throw SshException.OutputLimitExceeded(limit)
            bytes.write(chunk, 0, count)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
        const val STREAM_BUFFER = 16
        val EXIT_POLL = 20.milliseconds
    }
}
