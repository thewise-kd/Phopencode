package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs each command with this machine's `/bin/sh -c`, as a login shell gets an SSH command, in
 * [directory] and with only [environment]. Tests point it at temporary folders only.
 *
 * Output is read as it comes. Past `maxOutputBytes` it stops reading, ends the child it started
 * (with that child's descendants, nothing else) and fails with [SshException.OutputLimitExceeded],
 * never having held more than the limit. Past the request's timeout it ends the child the same way
 * and fails with [SshException.Timeout]; a cancelled call ends it the same way. Ending its own local child is a test-helper matter only;
 * production code never kills anything.
 */
internal class LocalShellExec(
    private val directory: Path,
    private val environment: Map<String, String>,
) : RemoteExec {
    /** Every command line run so far. */
    val commands: MutableList<String> = mutableListOf()

    /** The most bytes any one stream was allowed to hold, per run. */
    val limits: MutableList<Long> = mutableListOf()

    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult = withContext(Dispatchers.IO) {
        synchronized(commands) {
            commands += request.command
            limits += maxOutputBytes
        }
        val builder = ProcessBuilder("/bin/sh", "-c", request.command).directory(directory.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val child = builder.start()
        child.outputStream.use { stream -> request.stdin?.let(stream::write) }
        val overflow = AtomicBoolean(false)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(
            reader(child.inputStream, stdout, maxOutputBytes, overflow, child),
            reader(child.errorStream, stderr, maxOutputBytes, overflow, child),
        )
        val millis = request.timeout?.inWholeMilliseconds ?: DEFAULT_TIMEOUT_MILLIS
        // Interruptible, so that cancelling the call stops the wait; the child is then ended as on a timeout.
        val finished = try {
            runInterruptible { child.waitFor(millis, TimeUnit.MILLISECONDS) }
        } catch (cancelled: CancellationException) {
            end(child, readers)
            throw cancelled
        }
        if (!finished) {
            end(child, readers)
            if (overflow.get()) throw SshException.OutputLimitExceeded(maxOutputBytes)
            throw SshException.Timeout()
        }
        readers.forEach { it.join(READER_GRACE_MILLIS) }
        if (overflow.get()) {
            end(child, readers)
            throw SshException.OutputLimitExceeded(maxOutputBytes)
        }
        ExecResult(stdout.toByteArray(), stderr.toByteArray(), child.exitValue(), null)
    }

    private fun reader(input: InputStream, sink: ByteArrayOutputStream, limit: Long, overflow: AtomicBoolean, child: Process) =
        thread(isDaemon = true, name = "local-exec-reader") {
            val buffer = ByteArray(BUFFER_SIZE)
            runCatching {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (sink.size().toLong() + read > limit) {
                        overflow.set(true)
                        endChild(child)
                        break
                    }
                    sink.write(buffer, 0, read)
                }
            }
        }

    /** Ends this test's own child and what it started; nothing else. */
    private fun endChild(child: Process) {
        child.descendants().forEach { it.destroyForcibly() }
        child.destroyForcibly()
    }

    /** Ends the child, then closes the pipes and stops the readers. */
    private fun end(child: Process, readers: List<Thread>) {
        endChild(child)
        child.waitFor(READER_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        runCatching { child.inputStream.close() }
        runCatching { child.errorStream.close() }
        readers.forEach { it.join(READER_GRACE_MILLIS) }
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
        const val BUFFER_SIZE = 8192
    }
}
