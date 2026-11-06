package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Runs each command with this machine's `/bin/sh -c`, the way a login shell gets an SSH command,
 * in [directory] and with only [environment]. Tests point it at temporary folders only.
 *
 * stdin is the request's bytes, or closed at once. A command that runs past its timeout fails
 * with [SshException.Timeout] after its own child (and what that started) is ended and the reader
 * threads are stopped; nothing else is touched. This is a test helper on the build machine only.
 */
internal class LocalShellExec(
    private val directory: Path,
    private val environment: Map<String, String>,
) : RemoteExec {
    /** Every command line run so far. */
    val commands: MutableList<String> = mutableListOf()

    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult = withContext(Dispatchers.IO) {
        synchronized(commands) { commands += request.command }
        val builder = ProcessBuilder("/bin/sh", "-c", request.command).directory(directory.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = builder.start()
        val stdoutBox = AtomicReference(ByteArray(0))
        val stderrBox = AtomicReference(ByteArray(0))
        val readers = listOf(
            thread(isDaemon = true, name = "local-exec-stdout") { runCatching { stdoutBox.set(process.inputStream.readAllBytes()) } },
            thread(isDaemon = true, name = "local-exec-stderr") { runCatching { stderrBox.set(process.errorStream.readAllBytes()) } },
        )
        // Written on its own thread while the readers drain the output, so a command that answers
        // before it has read everything (`cat` with input larger than the pipe) cannot block us, and
        // the timeout below covers the writing too.
        val stdinBytes = request.stdin?.copyOf()
        val writer = thread(isDaemon = true, name = "local-exec-stdin") {
            runCatching { process.outputStream.use { stream -> stdinBytes?.let(stream::write) } }
        }
        val millis = request.timeout?.inWholeMilliseconds ?: DEFAULT_TIMEOUT_MILLIS
        if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
            // Only the child this test started, and what it started in turn.
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(READER_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            (readers + writer).forEach {
                it.interrupt()
                it.join(READER_GRACE_MILLIS)
            }
            throw SshException.Timeout()
        }
        (readers + writer).forEach { it.join(READER_GRACE_MILLIS) }
        val stdout = stdoutBox.get()
        val stderr = stderrBox.get()
        if (stdout.size > maxOutputBytes || stderr.size > maxOutputBytes) throw SshException.OutputLimitExceeded(maxOutputBytes)
        ExecResult(stdout, stderr, process.exitValue(), null)
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = flow {
        val result = run(request)
        emit(ExecEvent.Stdout(result.stdout))
        emit(ExecEvent.Stderr(result.stderr))
        emit(ExecEvent.Exited(result.exitCode, result.exitSignal))
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val READER_GRACE_MILLIS = 5_000L
    }
}
