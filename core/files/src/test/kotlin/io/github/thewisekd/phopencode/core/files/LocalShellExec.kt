package io.github.thewisekd.phopencode.core.files

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
 * in [directory] and with only [environment]. Tests choose `PATH`, so they decide whether `rg` and
 * `grep` exist. Nothing here touches the user's real data: tests point it at temporary folders.
 */
internal class LocalShellExec(
    private val directory: Path,
    private val environment: Map<String, String>,
) : RemoteExec {
    /** Every command line run so far. */
    val commands = mutableListOf<String>()

    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult = withContext(Dispatchers.IO) {
        synchronized(commands) { commands += request.command }
        val builder = ProcessBuilder("/bin/sh", "-c", request.command).directory(directory.toFile())
        builder.environment().apply {
            clear()
            putAll(environment)
        }
        val process = builder.start()
        process.outputStream.close()
        val stdoutBox = AtomicReference(ByteArray(0))
        val stderrBox = AtomicReference(ByteArray(0))
        val readers = listOf(
            thread(isDaemon = true, name = "local-exec-stdout") {
                runCatching { stdoutBox.set(process.inputStream.readAllBytes()) }
            },
            thread(isDaemon = true, name = "local-exec-stderr") {
                runCatching { stderrBox.set(process.errorStream.readAllBytes()) }
            },
        )
        val millis = request.timeout?.inWholeMilliseconds ?: DEFAULT_TIMEOUT_MILLIS
        if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
            // Only this test's own child and what it started.
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(READER_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            readers.forEach { it.join(READER_GRACE_MILLIS) }
            throw SshException.Timeout()
        }
        readers.forEach { it.join(READER_GRACE_MILLIS) }
        val stdout = stdoutBox.get()
        val stderr = stderrBox.get()
        if (stdout.size > maxOutputBytes || stderr.size > maxOutputBytes) throw SshException.OutputLimitExceeded(maxOutputBytes)
        ExecResult(stdout, stderr, process.exitValue(), null)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val READER_GRACE_MILLIS = 5_000L
    }

    override fun stream(request: ExecRequest): Flow<ExecEvent> = flow {
        val result = run(request)
        emit(ExecEvent.Stdout(result.stdout))
        emit(ExecEvent.Stderr(result.stderr))
        emit(ExecEvent.Exited(result.exitCode, result.exitSignal))
    }
}
