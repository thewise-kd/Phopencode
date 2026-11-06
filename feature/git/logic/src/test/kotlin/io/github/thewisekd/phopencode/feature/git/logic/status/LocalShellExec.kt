package io.github.thewisekd.phopencode.feature.git.logic.status

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
 * Runs each [ExecRequest.command] with this machine's `/bin/sh -c`, the way a login shell gets an
 * SSH command, in [directory] and with only [environment]. stdin is the request's bytes, or closed at
 * once. Tests point it at temporary folders only.
 *
 * A command that runs past its timeout fails with [SshException.Timeout] after its own local child
 * (and what that child started) is ended, as `RemoteExec` ends the remote process; see [endOwnChild].
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
        process.outputStream.use { stream -> request.stdin?.let(stream::write) }
        val stdoutBox = AtomicReference(ByteArray(0))
        val stderrBox = AtomicReference(ByteArray(0))
        val readers = listOf(
            thread(isDaemon = true, name = "status-exec-stdout") {
                runCatching { stdoutBox.set(process.inputStream.readAllBytes()) }
            },
            thread(isDaemon = true, name = "status-exec-stderr") {
                runCatching { stderrBox.set(process.errorStream.readAllBytes()) }
            },
        )
        val millis = request.timeout?.inWholeMilliseconds ?: Long.MAX_VALUE
        if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
            endOwnChild(process, readers)
            throw SshException.Timeout()
        }
        readers.forEach { it.join(READER_GRACE_MILLIS) }
        val stdout = stdoutBox.get()
        val stderr = stderrBox.get()
        if (stdout.size > maxOutputBytes || stderr.size > maxOutputBytes) {
            throw SshException.OutputLimitExceeded(maxOutputBytes)
        }
        ExecResult(stdout, stderr, process.exitValue(), null)
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = flow {
        val result = run(request)
        emit(ExecEvent.Stdout(result.stdout))
        emit(ExecEvent.Stderr(result.stderr))
        emit(ExecEvent.Exited(result.exitCode, result.exitSignal))
    }

    private companion object {
        const val READER_GRACE_MILLIS = 5_000L
    }
}

/**
 * Ends [process], a local child this test started itself, and the processes it started, then stops
 * [readers] and closes the streams. Test code only, on the machine running the tests; the app never
 * ends a process.
 */
internal fun endOwnChild(process: Process, readers: List<Thread>) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
    process.waitFor(5, TimeUnit.SECONDS)
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    readers.forEach {
        it.join(1_000)
        if (it.isAlive) it.interrupt()
    }
}
