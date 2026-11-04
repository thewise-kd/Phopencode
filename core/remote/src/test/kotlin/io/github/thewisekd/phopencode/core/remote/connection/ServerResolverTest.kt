package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerNote
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.start.ServerStartException
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ServerResolverTest {
    private val secrets = FakeSecretStore()
    private val tools = FakeServerTools()
    private val resolver = ServerResolver(secrets, tools)
    private val session = FakeSshSession()

    private suspend fun seedOpencode() = SecretValue.ofText(OPENCODE_PASSWORD).use { secrets.put(OPENCODE_REF, it) }

    private suspend fun failure(block: suspend () -> Unit): ConnectionError =
        assertFailsWith<ConnectionException> { block() }.error

    @Test
    fun `a running server is reached with the profile's credential`() = runTest {
        seedOpencode()
        resolver.resolve(session, profile()).use { server ->
            assertEquals("http://127.0.0.1:4096", server.baseUrl)
            assertEquals(OPENCODE_PASSWORD, server.passwords.single().revealText())
        }
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `a running server on IPv6 loopback gets a bracketed address`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Running("::1", 4096, 100, ServerSource.StateFile)
        resolver.resolve(session, profile()).use { assertEquals("http://[::1]:4096", it.baseUrl) }
    }

    @Test
    fun `a running server the app started is reached with the stored password`() = runTest {
        val stored = "test-" + "started"
        SecretValue.ofText(stored).use { secrets.put(ServerResolver.startedPasswordRef(profile()), it) }

        resolver.resolve(session, profile(opencodeCredential = null)).use {
            assertEquals(stored, it.passwords.single().revealText())
        }
    }

    @Test
    fun `a server the state file confirms tries the started password before the profile's`() = runTest {
        val started = "test-" + "started"
        seedOpencode()
        SecretValue.ofText(started).use { secrets.put(ServerResolver.startedPasswordRef(profile()), it) }
        tools.server = ServerLookup.Running("127.0.0.1", 4100, 200, ServerSource.StateFile)

        resolver.resolve(session, profile()).use { server ->
            assertEquals(listOf(started, OPENCODE_PASSWORD), server.passwords.map { it.revealText() })
        }
    }

    @Test
    fun `a server found by scanning tries the profile's credential first`() = runTest {
        val started = "test-" + "started"
        seedOpencode()
        SecretValue.ofText(started).use { secrets.put(ServerResolver.startedPasswordRef(profile()), it) }

        resolver.resolve(session, profile()).use { server ->
            assertEquals(listOf(OPENCODE_PASSWORD, started), server.passwords.map { it.revealText() })
        }
    }

    @Test
    fun `closing wipes every candidate`() = runTest {
        seedOpencode()
        SecretValue.ofText("test-" + "started").use { secrets.put(ServerResolver.startedPasswordRef(profile()), it) }
        val server = resolver.resolve(session, profile())
        server.close()
        server.passwords.forEach { assertFailsWith<IllegalStateException> { it.revealBytes() } }
    }

    @Test
    fun `an unresponsive server is never started next to, and the failure is retryable`() = runTest {
        seedOpencode()
        for (source in ServerSource.entries) {
            tools.server = ServerLookup.Unresponsive("127.0.0.1", 4096, 100, source)
            val error = failure { resolver.resolve(session, profile()) }
            assertEquals(ConnectionError.ServerUnresponsive, error)
            assertTrue(error.retryable)
        }
        assertTrue(tools.startedWith.isEmpty(), "no start next to a live server")
        assertTrue(tools.findBinaryCalls == 0, "not even the binary is looked up")
    }

    @Test
    fun `a live server without a usable loopback port is never started next to, and the failure is retryable`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Unusable(setOf(100L), setOf(ServerNote.NonLoopbackListener))
        val error = failure { resolver.resolve(session, profile()) }
        assertEquals(ConnectionError.ServerUnusable, error)
        assertTrue(error.retryable)
        assertTrue(tools.startedWith.isEmpty(), "no start next to a live server")
        assertEquals(0, tools.findBinaryCalls, "not even the binary is looked up")
    }

    @Test
    fun `a running server without any known password is an error`() = runTest {
        assertEquals(ConnectionError.ServerPasswordUnknown, failure { resolver.resolve(session, profile(opencodeCredential = null)) })
    }

    @Test
    fun `an unreadable opencode credential is an error`() = runTest {
        secrets.seedUnreadable(OPENCODE_REF)
        assertEquals(ConnectionError.SecretUnreadable, failure { resolver.resolve(session, profile()) })
    }

    @Test
    fun `starts a server with a fresh password it stores first`() = runTest {
        tools.server = ServerLookup.NotRunning()

        resolver.resolve(session, profile()).use { server ->
            assertEquals("http://127.0.0.1:4100", server.baseUrl)
            val (options, password) = tools.startedWith.single()
            assertEquals("/opt/opencode/bin/opencode", options.binary)
            assertEquals(password, server.passwords.single().revealText())
            assertTrue(password.length >= 40, "a generated password")
            val stored = assertIs<SecretReadResult.Found>(secrets.read(ServerResolver.startedPasswordRef(profile())))
            stored.value.use { assertEquals(password, it.revealText()) }
        }
    }

    @Test
    fun `a missing opencode is a clear error`() = runTest {
        tools.server = ServerLookup.NotRunning()
        tools.binary = BinaryLookup.Missing
        assertEquals(ConnectionError.OpencodeMissing, failure { resolver.resolve(session, profile()) })
    }

    @Test
    fun `failed discovery is reported with its reason`() = runTest {
        tools.server = ServerLookup.Failed(DetectFailure.TimedOut)
        assertEquals(
            ConnectionError.ServerDetectionFailed(DetectFailure.TimedOut),
            failure { resolver.resolve(session, profile()) },
        )
        tools.server = ServerLookup.NotRunning()
        tools.binary = BinaryLookup.Failed(DetectFailure.ToolsUnavailable)
        assertEquals(
            ConnectionError.ServerDetectionFailed(DetectFailure.ToolsUnavailable),
            failure { resolver.resolve(session, profile()) },
        )
    }

    @Test
    fun `a failed start is reported with its reason`() = runTest {
        tools.server = ServerLookup.NotRunning()
        tools.startResult = { throw ServerStartException.PortInUse() }
        assertEquals(
            ConnectionError.ServerStartFailed(ServerStartFailure.PortInUse),
            failure { resolver.resolve(session, profile()) },
        )
    }

    @Test
    fun `storage that cannot be reached stops before the server is started`() = runTest {
        tools.server = ServerLookup.NotRunning()
        secrets.unavailable = true
        assertEquals(ConnectionError.StorageUnavailable, failure { resolver.resolve(session, profile()) })
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `closing the result wipes the password`() = runTest {
        seedOpencode()
        val server = resolver.resolve(session, profile())
        server.close()
        assertFailsWith<IllegalStateException> { server.passwords.single().revealBytes() }
    }
}
