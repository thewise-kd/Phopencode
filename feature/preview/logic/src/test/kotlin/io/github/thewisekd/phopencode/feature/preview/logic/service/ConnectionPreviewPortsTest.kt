package io.github.thewisekd.phopencode.feature.preview.logic.service

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.remote.preview.ListeningPort
import io.github.thewisekd.phopencode.core.remote.preview.PortListFailure
import io.github.thewisekd.phopencode.core.remote.preview.PortListing
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewException
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPort
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortList
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionPreviewPortsTest {
    private suspend fun failure(block: suspend () -> Unit): PreviewFailure = assertFailsWith<PreviewException> { block() }.failure

    private suspend fun TestScope.connected(serverHost: String = "127.0.0.1"): PreviewConnection {
        val connection = PreviewConnection(backgroundScope, serverHost)
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    /** A lister that records what it was given and answers [answer], or throws [failure]. */
    private class Scripted(var answer: PortListing = PortListing.Listed(emptyList())) : PortLister {
        val calls = mutableListOf<Pair<RemoteExec, Int?>>()
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun list(exec: RemoteExec, opencodePort: Int?): PortListing {
            calls += exec to opencodePort
            gate?.await()
            failure?.let { throw it }
            return answer
        }
    }

    private fun ssOutput(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    @Test
    fun `without a connection the listing fails as not connected and runs nothing`() = runTest {
        val connection = PreviewConnection(backgroundScope)
        val lister = Scripted()

        assertEquals(PreviewFailure.NotConnected, failure { ConnectionPreviewPorts(connection.manager, lister).list() })
        assertEquals(PreviewFailure.NotConnected, failure { ConnectionPreviewPorts(connection.manager).list() })
        assertTrue(lister.calls.isEmpty())
    }

    @Test
    fun `lists the connected server's ports with the opencode port marked`() = runTest {
        val connection = connected()
        val exec = connection.sessions.single().exec
        exec.on("ss -ltnH") {
            stdout(
                ssOutput(
                    "LISTEN 0 511 0.0.0.0:8080 0.0.0.0:*",
                    "LISTEN 0 4096 127.0.0.1:$OPENCODE_PORT 0.0.0.0:*",
                    "LISTEN 0 128 10.0.0.5:22 0.0.0.0:*",
                    "LISTEN 0 128 *:5173 *:*",
                ),
            )
            exit(0)
        }

        val listing = ConnectionPreviewPorts(connection.manager).list()

        assertEquals(
            PreviewPortList.Listed(
                listOf(PreviewPort(OPENCODE_PORT, isOpencode = true), PreviewPort(5173, false), PreviewPort(8080, false)),
            ),
            listing,
        )
        assertEquals(listOf("ss -ltnH"), exec.executions.map { it.command }, "only the fixed listing runs")
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `the lister gets the connected session's command runner and the opencode port`() = runTest {
        val connection = connected()
        val lister = Scripted()

        ConnectionPreviewPorts(connection.manager, lister).list()

        val (exec, port) = lister.calls.single()
        assertSame(connection.sessions.single().exec, exec)
        assertEquals(OPENCODE_PORT, port)
    }

    @Test
    fun `an IPv6 opencode address is understood`() = runTest {
        val connection = connected(serverHost = "::1")
        val lister = Scripted()

        ConnectionPreviewPorts(connection.manager, lister).list()

        assertEquals(OPENCODE_PORT, lister.calls.single().second)
    }

    @Test
    fun `the listing's ports reach the contract in order`() = runTest {
        val connection = connected()
        val lister = Scripted(
            PortListing.Listed(listOf(ListeningPort(3000, isOpencode = false), ListeningPort(OPENCODE_PORT, isOpencode = true))),
        )

        assertEquals(
            PreviewPortList.Listed(listOf(PreviewPort(3000, false), PreviewPort(OPENCODE_PORT, true))),
            ConnectionPreviewPorts(connection.manager, lister).list(),
        )
    }

    @Test
    fun `an empty listing is an empty list`() = runTest {
        val connection = connected()

        assertEquals(PreviewPortList.Listed(emptyList()), ConnectionPreviewPorts(connection.manager, Scripted()).list())
    }

    @Test
    fun `an unsupported server is unsupported`() = runTest {
        val connection = connected()

        assertEquals(
            PreviewPortList.Unsupported,
            ConnectionPreviewPorts(connection.manager, Scripted(PortListing.Unsupported)).list(),
        )
    }

    @Test
    fun `every listing failure maps to a contract failure`() = runTest {
        val connection = connected()
        val expected = mapOf(
            PortListFailure.TimedOut to PreviewFailure.TimedOut,
            PortListFailure.OutputTooLarge to PreviewFailure.OutputTooLarge,
            PortListFailure.CommandFailed to PreviewFailure.CommandFailed,
            PortListFailure.ConnectionLost to PreviewFailure.ConnectionLost,
        )
        assertEquals(PortListFailure.entries.toSet(), expected.keys, "every failure is mapped")

        for ((reason, mapped) in expected) {
            val ports = ConnectionPreviewPorts(connection.manager, Scripted(PortListing.Failed(reason)))
            assertEquals(mapped, failure { ports.list() }, reason.name)
        }
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `the real lister's failures reach the contract mapped`() = runTest {
        val connection = connected()
        val exec = connection.sessions.single().exec
        val ports = ConnectionPreviewPorts(connection.manager)

        exec.failNext(SshException.Timeout())
        assertEquals(PreviewFailure.TimedOut, failure { ports.list() })
    }

    @Test
    fun `a connection that ends during the listing is lost`() = runTest {
        val connection = connected()
        val lister = Scripted().apply { gate = CompletableDeferred() }
        val ports = ConnectionPreviewPorts(connection.manager, lister)

        val listing = async { runCatching { ports.list() } }
        runCurrent()
        assertEquals(1, lister.calls.size)
        connection.sessions.single().loseConnection()
        runCurrent()

        val thrown = assertIs<PreviewException>(listing.await().exceptionOrNull())
        assertEquals(PreviewFailure.ConnectionLost, thrown.failure)
    }

    @Test
    fun `an unexpected failure is unknown and keeps no cause`() = runTest {
        val connection = connected()
        val lister = Scripted().apply { failure = IOException("/home/tester/secret path") }

        val thrown = assertFailsWith<PreviewException> { ConnectionPreviewPorts(connection.manager, lister).list() }

        assertEquals(PreviewFailure.Unknown, thrown.failure)
        assertNull(thrown.cause)
        assertFalse("tester" in thrown.message.orEmpty())
    }

    @Test
    fun `a not connected failure from inside the block is lost, not unknown`() = runTest {
        val connection = connected()
        val lister = Scripted().apply { failure = NotConnectedException() }

        assertEquals(PreviewFailure.ConnectionLost, failure { ConnectionPreviewPorts(connection.manager, lister).list() })
    }

    @Test
    fun `cancellation is never wrapped`() = runTest {
        val connection = connected()
        val lister = Scripted().apply { failure = CancellationException("stop") }

        assertFailsWith<CancellationException> { ConnectionPreviewPorts(connection.manager, lister).list() }
    }

    @Test
    fun `the opencode port is read from the server address`() {
        assertEquals(4096, opencodePortOf("http://127.0.0.1:4096"))
        assertEquals(4096, opencodePortOf("http://[::1]:4096"))
        assertEquals(65535, opencodePortOf("http://localhost:65535"))
        assertNull(opencodePortOf("http://127.0.0.1"))
        assertNull(opencodePortOf("http://127.0.0.1:0"))
        assertNull(opencodePortOf("http://127.0.0.1:70000"))
        assertNull(opencodePortOf("not a url ::"))
        assertNull(opencodePortOf(null))
    }
}
