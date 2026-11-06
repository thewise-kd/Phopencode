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

    /** Answers the three steps of an untracked diff: status, folder check, diff. */
    private fun untrackedExec(
        status: ExecResult = ok("# branch.oid (initial)\u0000? a/b.txt\u0000"),
        folder: ExecResult = ok("$dir\na/\n"),
        diff: ExecResult = ExecResult("diff --git a/a/b.txt b/a/b.txt\n".toByteArray(), ByteArray(0), 1, null),
    ) = ScriptedExec { request, _ ->
        when {
            "porcelain" in request.command -> status
            "rev-parse" in request.command -> folder
            "--no-index" in request.command -> diff
            else -> error("unexpected command")
        }
    }

    private fun ok(stdout: String) = ExecResult(stdout.toByteArray(), ByteArray(0), 0, null)

    @Test
    fun `an untracked diff checks the path, then reads the diff under the caller's limit`() = runTest {
        val exec = untrackedExec()
        val diff = assertIs<UntrackedDiff.Diff>(GitRunner(exec).diffUntracked(dir, "a/b.txt", maxOutputBytes = 777))
        assertEquals("diff --git a/a/b.txt b/a/b.txt\n", String(diff.output))
        assertEquals(3, exec.requests.size)
        assertTrue("porcelain" in exec.requests[0].command)
        assertTrue("rev-parse" in exec.requests[1].command)
        assertEquals(GitCommands.diffUntracked(dir, "a/b.txt").commandLine, exec.requests[2].command)
        assertEquals(777L, exec.limits[2])
        assertFalse("diff --git" in diff.toString())
    }

    @Test
    fun `an untracked diff with exit 0 is a diff too`() = runTest {
        val exec = untrackedExec(diff = ok(""))
        assertIs<UntrackedDiff.Diff>(GitRunner(exec).diffUntracked(dir, "a/b.txt"))
    }

    @Test
    fun `a path status does not list as untracked is refused before the diff`() = runTest {
        val notListed = listOf(
            "# branch.oid (initial)\u0000",
            "1 .M N... 100644 100644 100644 0 0 a/b.txt\u0000",
            "! a/b.txt\u0000",
            "? a/b.txt/\u0000",
            "? a/b.txtx\u0000",
        )
        notListed.forEach { status ->
            val exec = untrackedExec(status = ok(status))
            assertEquals(UntrackedDiff.Refused, GitRunner(exec).diffUntracked(dir, "a/b.txt"), status)
            assertEquals(1, exec.requests.size)
        }
    }

    @Test
    fun `a folder that resolves elsewhere is refused before the diff`() = runTest {
        val elsewhere = listOf(ok("$dir\nx/\n"), ok("/other\na/\n"), ok("$dir\na/\nmore\n"), ok("$dir\na\n"), ExecResult(ByteArray(0), "fatal: cannot change to".toByteArray(), 128, null))
        elsewhere.forEach { folder ->
            val exec = untrackedExec(folder = folder)
            assertEquals(UntrackedDiff.Refused, GitRunner(exec).diffUntracked(dir, "a/b.txt"))
            assertEquals(2, exec.requests.size)
        }
        val top = untrackedExec(status = ok("? b.txt\u0000"), folder = ok("$dir\n\n"))
        assertIs<UntrackedDiff.Diff>(GitRunner(top).diffUntracked(dir, "b.txt"))
        val wrongTop = untrackedExec(status = ok("? b.txt\u0000"), folder = ok("$dir\nsub/\n"))
        assertEquals(UntrackedDiff.Refused, GitRunner(wrongTop).diffUntracked(dir, "b.txt"))
    }

    @Test
    fun `a diff that printed nothing but an error, or a failed status, is a failure`() = runTest {
        val noDiff = untrackedExec(diff = ExecResult(ByteArray(0), "error: Could not access 'a/b.txt'".toByteArray(), 1, null))
        assertEquals(1, assertIs<UntrackedDiff.Failed>(GitRunner(noDiff).diffUntracked(dir, "a/b.txt")).result.exitCode)
        val crashed = untrackedExec(diff = ExecResult(ByteArray(0), ByteArray(0), 128, null))
        assertIs<UntrackedDiff.Failed>(GitRunner(crashed).diffUntracked(dir, "a/b.txt"))
        val noRepo = untrackedExec(status = ExecResult(ByteArray(0), "fatal: not a git repository".toByteArray(), 128, null))
        assertEquals(128, assertIs<UntrackedDiff.Failed>(GitRunner(noRepo).diffUntracked(dir, "a/b.txt")).result.exitCode)
    }

    @Test
    fun `an untracked diff that prints too much is OutputTooLarge`() = runTest {
        val exec = ScriptedExec { request, limit ->
            when {
                "porcelain" in request.command -> ok("? a/b.txt\u0000")
                "rev-parse" in request.command -> ok("$dir\na/\n")
                else -> throw SshException.OutputLimitExceeded(limit)
            }
        }
        assertEquals(10L, assertFailsWith<GitException.OutputTooLarge> { GitRunner(exec).diffUntracked(dir, "a/b.txt", maxOutputBytes = 10) }.limitBytes)
    }

    @Test
    fun `exception messages are fixed text`() {
        listOf(GitException.TimedOut(null), GitException.OutputTooLarge(5, null), GitException.ConnectionLost(null))
            .forEach { assertTrue(it.message!!.isNotBlank()) }
    }
}
