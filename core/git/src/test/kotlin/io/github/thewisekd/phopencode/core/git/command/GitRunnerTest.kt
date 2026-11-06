package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class GitRunnerTest {
    private val dir = "/home/u/project"

    private class ScriptedExec(private val block: suspend (ExecRequest, Long) -> ExecResult) : RemoteExec {
        val requests = mutableListOf<ExecRequest>()
        val limits = mutableListOf<Long>()

        override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
            requests += request
            limits += maxOutputBytes
            return block(request, maxOutputBytes)
        }

        override fun stream(request: ExecRequest): Flow<ExecEvent> = emptyFlow()
    }

    @Test
    fun `a non-zero exit is a result`() = runTest {
        val exec = ScriptedExec { _, _ -> ExecResult("out".toByteArray(), "fatal: nope\n".toByteArray(), 128, null) }
        val result = GitRunner(exec).run(GitCommands.status(dir))
        assertFalse(result.succeeded)
        assertEquals(128, result.exitCode)
        assertContentEquals("out".toByteArray(), result.stdout)
        assertEquals("fatal: nope\n", result.stderrText())
        assertFalse("nope" in result.toString())
    }

    @Test
    fun `the request carries the command line, the timeout and the limit`() = runTest {
        val exec = ScriptedExec { _, _ -> ExecResult(ByteArray(0), ByteArray(0), 0, null) }
        val command = GitCommands.fetch(dir)
        val result = GitRunner(exec).run(command, maxOutputBytes = 1234)
        assertTrue(result.succeeded)
        assertEquals(command.commandLine, exec.requests.single().command)
        assertEquals(command.timeout, exec.requests.single().timeout)
        assertEquals(1234L, exec.limits.single())
        GitRunner(exec).run(command)
        assertEquals(RemoteExec.DEFAULT_MAX_OUTPUT_BYTES, exec.limits.last())
    }

    @Test
    fun `the commit message stdin is wiped after the run but the command can run again`() = runTest {
        var seen: ByteArray? = null
        val exec = ScriptedExec { request, _ ->
            seen = request.stdin
            assertContentEquals("msg".toByteArray(), request.stdin)
            ExecResult(ByteArray(0), ByteArray(0), 0, null)
        }
        val command = GitCommands.commit(dir, "msg")
        GitRunner(exec).run(command)
        assertTrue(seen!!.all { it == 0.toByte() })
        GitRunner(exec).run(command)
    }

    @Test
    fun `a timeout becomes TimedOut`() = runTest {
        val cause = SshException.Timeout()
        val thrown = assertFailsWith<GitException.TimedOut> {
            GitRunner(ScriptedExec { _, _ -> throw cause }).run(GitCommands.status(dir))
        }
        assertSame(cause, thrown.cause)
    }

    @Test
    fun `too much output becomes OutputTooLarge with the limit`() = runTest {
        val thrown = assertFailsWith<GitException.OutputTooLarge> {
            GitRunner(ScriptedExec { _, limit -> throw SshException.OutputLimitExceeded(limit) }).run(GitCommands.status(dir), 99)
        }
        assertEquals(99L, thrown.limitBytes)
    }

    @Test
    fun `a lost or failed connection becomes ConnectionLost`() = runTest {
        listOf(SshException.Disconnected(), SshException.ConnectionFailed(), SshException.AuthenticationFailed()).forEach { cause ->
            val thrown = assertFailsWith<GitException.ConnectionLost> {
                GitRunner(ScriptedExec { _, _ -> throw cause }).run(GitCommands.status(dir))
            }
            assertSame(cause, thrown.cause)
        }
    }

    @Test
    fun `a killed process is a result without an exit code`() = runTest {
        val result = GitRunner(ScriptedExec { _, _ -> ExecResult(ByteArray(0), ByteArray(0), null, "KILL") }).run(GitCommands.status(dir))
        assertFalse(result.succeeded)
        assertEquals(null, result.exitCode)
        assertEquals("KILL", result.exitSignal)
    }

    @Test
    fun `cancellation is not turned into an error`() = runTest {
        val started = CompletableDeferred<Unit>()
        var caught: Throwable? = null
        val job = launch {
            try {
                GitRunner(ScriptedExec { _, _ -> started.complete(Unit); awaitCancellation() }).run(GitCommands.fetch(dir))
            } catch (e: Throwable) {
                caught = e
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertIs<CancellationException>(caught)
    }

    @Test
    fun `exception messages are fixed text`() {
        listOf(GitException.TimedOut(null), GitException.OutputTooLarge(5, null), GitException.ConnectionLost(null))
            .forEach { assertTrue(it.message!!.isNotBlank()) }
    }
}
