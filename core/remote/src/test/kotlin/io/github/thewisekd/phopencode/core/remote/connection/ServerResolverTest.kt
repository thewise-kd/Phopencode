package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.detect.BinaryLookup
import io.github.thewisekd.phopencode.core.remote.detect.BinarySource
import io.github.thewisekd.phopencode.core.remote.detect.DetectFailure
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerNote
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.remote.detect.VersionCheck
import io.github.thewisekd.phopencode.core.remote.start.ServerStartOptions
import io.github.thewisekd.phopencode.core.remote.start.ServerStartException
import io.github.thewisekd.phopencode.core.remote.start.StartedServer
import io.github.thewisekd.phopencode.core.storage_api.SecretReadResult
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import io.github.thewisekd.phopencode.core.testing.storage.FakeSecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
    fun `without a port the lookup is asked for none, as before`() = runTest {
        seedOpencode()
        resolver.resolve(session, profile()).use { assertEquals("http://127.0.0.1:4096", it.baseUrl) }
        assertEquals(listOf<Int?>(null), tools.findServerPorts)
    }

    @Test
    fun `with a port the lookup is asked for it and its server is reached`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Running("127.0.0.1", 4096, 3400, ServerSource.ProcessScan)
        resolver.resolve(session, profile(opencodePort = 4096)).use { server ->
            assertEquals("http://127.0.0.1:4096", server.baseUrl)
            assertEquals(listOf(OPENCODE_PASSWORD), server.passwords.map { it.revealText() })
        }
        assertEquals(listOf<Int?>(4096), tools.findServerPorts)
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `with a port, a server found on another port is never used`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Running("127.0.0.1", 49374, 1200, ServerSource.ProcessScan)
        val error = failure { resolver.resolve(session, profile(opencodePort = 4096)) }
        assertEquals(ConnectionError.ServerNotOnPort(4096), error)
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `with a port and a server only elsewhere, nothing is started and the failure is retryable`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.NotOnPort(4096, setOf(1200L))
        val error = failure { resolver.resolve(session, profile(opencodePort = 4096)) }
        assertEquals(ConnectionError.ServerNotOnPort(4096), error)
        assertTrue(error.retryable)
        assertTrue(tools.startedWith.isEmpty(), "no start next to a live server")
        assertEquals(0, tools.findBinaryCalls, "not even the binary is looked up")
    }

    @Test
    fun `with a port and no server at all, one is started on that port`() = runTest {
        tools.server = ServerLookup.NotRunning()
        tools.startResult = { StartedServer("http://127.0.0.1:${it.port}", checkNotNull(it.port), 200) }
        resolver.resolve(session, profile(opencodePort = 4096)).use { server ->
            assertEquals("http://127.0.0.1:4096", server.baseUrl)
            val (options, _) = tools.startedWith.single()
            assertEquals(4096, options.port)
            assertEquals("/opt/opencode/bin/opencode", options.binary)
        }
    }

    @Test
    fun `without a port a started server picks its own`() = runTest {
        tools.server = ServerLookup.NotRunning()
        resolver.resolve(session, profile()).use { }
        assertEquals(null, tools.startedWith.single().first.port)
    }

    @Test
    fun `with a port held by a server that does not answer, it is unresponsive`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Unresponsive("127.0.0.1", 4096, 3400, ServerSource.ProcessScan)
        assertEquals(ConnectionError.ServerUnresponsive, failure { resolver.resolve(session, profile(opencodePort = 4096)) })
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `with a port on a non-loopback address only, it is unusable`() = runTest {
        seedOpencode()
        tools.server = ServerLookup.Unusable(setOf(3400L), setOf(ServerNote.NonLoopbackListener))
        assertEquals(ConnectionError.ServerUnusable, failure { resolver.resolve(session, profile(opencodePort = 4096)) })
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `a stored port out of range is an invalid profile, and nothing is looked up`() = runTest {
        for (port in listOf(0, -1, 65536)) {
            assertEquals(ConnectionError.InvalidProfile, failure { resolver.resolve(session, profile(opencodePort = port)) })
        }
        assertEquals(0, tools.findServerCalls)
        assertTrue(tools.startedWith.isEmpty())
    }

    @Test
    fun `closing the result wipes the password`() = runTest {
        seedOpencode()
        val server = resolver.resolve(session, profile())
        server.close()
        assertFailsWith<IllegalStateException> { server.passwords.single().revealBytes() }
    }

    // One start per profile at a time. Each test uses a profile ID of its own, because the lock is
    // shared by every resolver in the process.

    private fun lockProfile(name: String) = profile().copy(id = ServerProfileId("lock-test-$name"))

    private suspend fun stored(profile: io.github.thewisekd.phopencode.core.model.ServerProfile): String =
        assertIs<SecretReadResult.Found>(secrets.read(ServerResolver.startedPasswordRef(profile))).value.use { it.revealText() }

    @Test
    fun `two resolves that find no server start one after the other, and the second uses the running one`() = runTest {
        val host = StartingServerTools()
        val first = ServerResolver(secrets, host)
        val second = ServerResolver(secrets, host)
        val target = lockProfile("serial")
        val gate = CompletableDeferred<Unit>().also { host.startGates += it }

        val a = async { first.resolve(session, target) }
        runCurrent()
        val b = async { second.resolve(session, target) }
        runCurrent()
        assertEquals(1, host.started.size, "the second waits while the first starts")
        assertEquals(listOf("find", "start", "find"), host.events)

        gate.complete(Unit)
        a.await().use { server -> assertEquals(listOf(host.started.single()), server.passwords.map { it.revealText() }) }
        b.await().use { server ->
            assertEquals("http://127.0.0.1:4100", server.baseUrl)
            assertTrue(host.started.single() in server.passwords.map { it.revealText() }, "the stored password of the running server")
        }
        assertEquals(1, host.started.size, "only one server was started")
        assertEquals(listOf("find", "start", "find", "started", "find"), host.events, "the second looked again after the first")
        assertEquals(host.started.single(), stored(target), "the stored password matches the server that runs")
    }

    @Test
    fun `after a failed start the next waiting resolve may start`() = runTest {
        val host = StartingServerTools()
        val resolver = ServerResolver(secrets, host)
        val target = lockProfile("failed")
        val gate = CompletableDeferred<Unit>().also { host.startGates += it }
        host.failures += true

        val a = async { runCatching { resolver.resolve(session, target) } }
        runCurrent()
        val b = async { resolver.resolve(session, target) }
        runCurrent()
        assertEquals(1, host.started.size)

        gate.complete(Unit)
        val error = assertIs<ConnectionException>(a.await().exceptionOrNull()).error
        assertEquals(ConnectionError.ServerStartFailed(ServerStartFailure.PortInUse), error)
        b.await().use { server -> assertEquals(listOf(host.started[1]), server.passwords.map { it.revealText() }) }
        assertEquals(2, host.started.size)
        assertEquals(host.started[1], stored(target))
    }

    @Test
    fun `a failed start does not keep the next call from starting`() = runTest {
        val host = StartingServerTools()
        val resolver = ServerResolver(secrets, host)
        val target = lockProfile("sequential")
        host.failures += true
        assertIs<ConnectionError.ServerStartFailed>(failure { resolver.resolve(session, target) })
        resolver.resolve(session, target).use { server -> assertEquals(host.started[1], server.passwords.single().revealText()) }
        assertEquals(2, host.started.size)
    }

    @Test
    fun `different profiles do not wait for each other`() = runTest {
        val host = StartingServerTools()
        val first = ServerResolver(secrets, host)
        val second = ServerResolver(secrets, host)
        val one = lockProfile("one")
        val two = lockProfile("two")
        val gate = CompletableDeferred<Unit>().also { host.startGates += it }

        val a = async { first.resolve(session, one) }
        runCurrent()
        assertEquals(1, host.started.size)
        second.resolve(session, two).use { server -> assertEquals(host.started[1], server.passwords.single().revealText()) }
        assertTrue(a.isActive, "the first start is still running")

        gate.complete(Unit)
        a.await().use { server -> assertEquals(host.started[0], server.passwords.single().revealText()) }
        assertEquals(host.started[0], stored(one))
        assertEquals(host.started[1], stored(two))
    }

    @Test
    fun `cancelling a start releases the lock`() = runTest {
        val host = StartingServerTools()
        val resolver = ServerResolver(secrets, host)
        val target = lockProfile("cancel")
        CompletableDeferred<Unit>().also { host.startGates += it }

        val a = async { resolver.resolve(session, target) }
        runCurrent()
        val b = async { resolver.resolve(session, target) }
        runCurrent()
        assertEquals(1, host.started.size, "the second waits")

        a.cancel()
        runCurrent()
        b.await().use { server -> assertEquals(host.started[1], server.passwords.single().revealText()) }
        assertEquals(2, host.started.size)
        assertEquals(host.started[1], stored(target))
    }
}

/**
 * A server host that runs nothing until a start completes; then a server this app started runs.
 * Each start can be held open by the gate of its turn and can fail by the flag of its turn.
 */
private class StartingServerTools : ServerTools {
    var server: ServerLookup = ServerLookup.NotRunning()
    val startGates = mutableListOf<CompletableDeferred<Unit>>()
    val failures = mutableListOf<Boolean>()
    val started = mutableListOf<String>()
    val events = mutableListOf<String>()

    override suspend fun findServer(session: SshSession): ServerLookup {
        events += "find"
        return server
    }

    override suspend fun findBinary(session: SshSession): BinaryLookup =
        BinaryLookup.Found("/opt/opencode/bin/opencode", BinarySource.KnownLocation, VersionCheck.Unparseable)

    override suspend fun start(session: SshSession, password: SecretValue, options: ServerStartOptions): StartedServer {
        val turn = started.size
        started += password.revealText()
        events += "start"
        startGates.getOrNull(turn)?.await()
        if (failures.getOrNull(turn) == true) throw ServerStartException.PortInUse()
        server = ServerLookup.Running("127.0.0.1", 4100, 200, ServerSource.StateFile)
        events += "started"
        return StartedServer("http://127.0.0.1:4100", 4100, 200)
    }
}
