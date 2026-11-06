package io.github.thewisekd.phopencode.feature.git.logic.remote

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.status.endOwnChild
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Runs each [ExecRequest.command] with this machine's `/bin/sh -c` in [directory] with only
 * [environment], as the status tests' `LocalShellExec` does, but cancellable the way `RemoteExec`
 * is: cancelling the calling coroutine, or the request's timeout, ends the local child this test
 * started (and what that child started, see [endOwnChild]) and nothing else.
 */
internal class CancellableShellExec(
    private val directory: Path,
    private val environment: Map<String, String>,
) : RemoteExec {
    /** Every command line run so far. */
    val commands: MutableList<String> = mutableListOf()

    /** Commands that were ended by a cancellation, counted after their child was gone. */
    @Volatile
    var ended: Int = 0
        private set

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
            thread(isDaemon = true, name = "remote-exec-stdout") { runCatching { stdoutBox.set(process.inputStream.readAllBytes()) } },
            thread(isDaemon = true, name = "remote-exec-stderr") { runCatching { stderrBox.set(process.errorStream.readAllBytes()) } },
        )
        val deadline = request.timeout?.let { System.nanoTime() + it.inWholeNanoseconds }
        var finished = false
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                ensureActive()
                if (deadline != null && System.nanoTime() > deadline) throw SshException.Timeout()
            }
            finished = true
        } finally {
            if (!finished) {
                endOwnChild(process, readers)
                ended++
            }
        }
        readers.forEach { it.join(READER_GRACE_MILLIS) }
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
        const val POLL_MILLIS = 20L
        const val READER_GRACE_MILLIS = 5_000L
    }
}
