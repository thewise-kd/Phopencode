package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val VERSION = "git --version"

@OptIn(ExperimentalCoroutinesApi::class)
class GitConnectionTest {
    private suspend fun failure(block: suspend () -> Unit): GitFailure = assertFailsWith<GitException> { block() }.failure

    private suspend fun TestScope.connected(): GitTestConnection {
        val connection = GitTestConnection(backgroundScope)
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    @Test
    fun `commands run on the active connection's session`() = runTest {
        val connection = connected()
        val exec = connection.lastSession().exec
        exec.on(VERSION) {
            stdout("git version 2.43.0\n")
            exit(0)
        }

        val result = ConnectionGitConnection(connection.manager).withExec { it.run(ExecRequest(VERSION)) }

        assertEquals(0, result.exitCode)
        assertEquals("git version 2.43.0\n", result.stdout.decodeToString())
        assertEquals(listOf(VERSION), exec.executions.map { it.command })
    }

    @Test
    fun `without a connection nothing runs`() = runTest {
        val connection = GitTestConnection(backgroundScope)
        var ran = false

        assertEquals(GitFailure.NotConnected, failure { ConnectionGitConnection(connection.manager).withExec { ran = true } })
        assertEquals(false, ran)
    }

    @Test
    fun `a connection lost while a command runs fails as not connected`() = runTest {
        val connection = connected()
        val session = connection.lastSession()
        val started = CompletableDeferred<Unit>()
        session.exec.on(VERSION) {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            exit(0)
        }

        val call = async { failure { ConnectionGitConnection(connection.manager).withExec { it.run(ExecRequest(VERSION)) } } }
        started.await()
        session.loseConnection()
        runCurrent()

        assertEquals(GitFailure.NotConnected, call.await())
    }

    @Test
    fun `ssh failures of a command map to git failures`() = runTest {
        val connection = connected()
        val exec = connection.lastSession().exec
        val git = ConnectionGitConnection(connection.manager)

        exec.failNext(SshException.Timeout())
        assertEquals(GitFailure.TimedOut, failure { git.withExec { it.run(ExecRequest(VERSION)) } })

        exec.failNext(SshException.OutputLimitExceeded(10))
        assertEquals(GitFailure.OutputTooLarge, failure { git.withExec { it.run(ExecRequest(VERSION)) } })
    }

    @Test
    fun `a git failure from the block passes unchanged`() = runTest {
        val connection = connected()
        val thrown = GitException(GitFailure.NotRepository)

        val caught = assertFailsWith<GitException> { ConnectionGitConnection(connection.manager).withExec { throw thrown } }

        assertSame(thrown, caught)
    }

    @Test
    fun `cancellation is never wrapped`() = runTest {
        val connection = connected()

        assertFailsWith<CancellationException> {
            ConnectionGitConnection(connection.manager).withExec { throw CancellationException("stop") }
        }
        assertFailsWith<CancellationException> { ExecGitConnection(FakeRemoteExec()).withExec { throw CancellationException("stop") } }
    }

    @Test
    fun `any other failure becomes unknown without its cause`() = runTest {
        val connection = connected()
        val secretPath = listOf("/srv", "gizli", "token.env").joinToString("/")

        val caught = assertFailsWith<GitException> {
            ConnectionGitConnection(connection.manager).withExec { throw IllegalStateException(secretPath) }
        }

        assertEquals(GitFailure.Unknown, caught.failure)
        assertNull(caught.cause)
    }

    @Test
    fun `a given command runner is used as it is, with the same failures`() = runTest {
        val exec = FakeRemoteExec()
        exec.on(VERSION) { exit(0) }
        val git = ExecGitConnection(exec)

        assertEquals(0, git.withExec { it.run(ExecRequest(VERSION)) }.exitCode)

        exec.failNext(SshException.ConnectionFailed())
        assertEquals(GitFailure.Unreachable, failure { git.withExec { it.run(ExecRequest(VERSION)) } })

        exec.disconnect()
        assertEquals(GitFailure.NotConnected, failure { git.withExec { it.run(ExecRequest(VERSION)) } })

        assertEquals(GitFailure.NotConnected, failure { git.withExec { throw NotConnectedException() } })
    }
}
