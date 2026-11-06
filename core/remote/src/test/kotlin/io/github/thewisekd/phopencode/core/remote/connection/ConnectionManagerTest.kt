package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.SecretRef
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.model.SshAuth
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.api.FakeEventApi
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConnectionManagerTest {
    private fun ConnectionState.kind(): String = this::class.simpleName.orEmpty()

    private suspend fun TestScope.connected(fx: Fixture): ConnectionState.Connected {
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        return assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    private fun Fixture.assertNothingOpen() {
        assertTrue(connector.sessions.all { !it.connected.value }, "a session is still open")
        assertTrue(clients.made.all { it.client.control.isClosed }, "a client is still open")
        assertEquals(0, connector.halfOpenConnections, "a connection is half open")
        assertTrue(clients.made.all { it.client.events.activeConnections.value == 0 }, "an event stream is still open")
    }

    private fun Fixture.assertNoReconnectAttempted() {
        assertTrue(states.none { it is ConnectionState.Reconnecting || it is ConnectionState.Error }, "states: $states")
    }

    /** The latest client's event stream, which the manager watches through a collection of its own. */
    private fun Fixture.latestEvents(): FakeEventApi = clients.made.last().client.events

    /** After a server-side drop of the first connection: waits out the delay and checks the new connection. */
    private fun TestScope.assertReconnectsAfterServerDrop(fx: Fixture, first: ConnectionState.Connected) {
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.ServerLost), fx.manager.state.value)
        assertTrue(fx.clients.made.single().client.control.isClosed, "the old client is closed at once")
        assertFalse(fx.connector.sessions.single().connected.value, "the old session is closed at once")

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()

        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.sessions.size)
        assertEquals(2, fx.clients.made.size)
        assertSame(fx.clients.made.last().client, second.client)
        assertEquals(first.generation + 1, second.generation)
        assertEquals(2, fx.tools.findServerCalls, "the server step runs again")
        assertEquals(
            listOf("Reconnecting", "Ssh", "Server", "Api", "Connected"),
            fx.states.takeLast(5).map { it.kind() },
        )
        assertEquals(1, fx.latestEvents().activeConnections.value, "the new connection is watched")
    }

    @Test
    fun `watches the server through an event stream of its own`() = runTest {
        val fx = Fixture(this)
        val connected = connected(fx)
        val events = fx.latestEvents()
        assertEquals(1, events.activeConnections.value)

        val seen = mutableListOf<ServerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connected.client.events.events().collect { seen += it }
        }
        events.awaitConnections(2)
        val deleted = ServerEvent.SessionDeleted(SessionId("s-1"))
        events.emit(deleted)
        runCurrent()

        assertEquals(listOf(ServerEvent.Connected(), deleted), seen, "the app's collector misses nothing")
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    /** Every value the recorded spec allows for `GET /api/event`'s `types` query parameter. */
    private fun specEventTypes(): Set<String> {
        val text = checkNotNull(javaClass.getResource("/fixtures/spec/openapi.json")) { "spec fixture missing" }.readText()
        val types = Json.parseToJsonElement(text).jsonObject.getValue("paths").jsonObject
            .getValue("/api/event").jsonObject.getValue("get").jsonObject.getValue("parameters").jsonArray
            .map { it.jsonObject }
            .single { it["name"]?.jsonPrimitive?.content == "types" }
        val found = mutableSetOf<String>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "enum") value.jsonArray.forEach { found += it.jsonPrimitive.content } else collect(value)
                }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(types.getValue("schema"))
        return found
    }

    @Test
    fun `the watch asks only for event types the server's spec allows`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        val requested = fx.latestEvents().requestedTypes.single()
        val allowed = specEventTypes()
        assertTrue("location.shutdown" in allowed, "the spec fixture lost the shutdown event")
        assertTrue(requested.isNotEmpty(), "an empty filter asks for every event")
        assertTrue(allowed.containsAll(requested), "not in the spec's types enum: ${requested - allowed}")
        assertEquals(setOf("location.shutdown"), requested)
    }

    @Test
    fun `the server's shutdown notice is taken for a server drop`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)

        fx.latestEvents().emit(ServerEvent.Unknown("location.shutdown"))
        runCurrent()

        assertReconnectsAfterServerDrop(fx, first)
    }

    @Test
    fun `other events on the watch do not drop the connection`() = runTest {
        val fx = Fixture(this)
        val connected = connected(fx)

        fx.latestEvents().emit(ServerEvent.Connected())
        fx.latestEvents().emit(ServerEvent.Unknown("location.other"))
        runCurrent()

        assertSame(connected, fx.manager.state.value)
    }

    @Test
    fun `an event stream that never opens uses up the attempts and ends in an error`() = runTest {
        val fx = Fixture(this)
        // The info call answers every time, but the stream fails to open with a retryable error.
        fx.clients.refuseEvents = ApiError.Network
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        assertTrue(fx.states.any { it is ConnectionState.Connected }, "the info call answered")
        assertEquals(
            ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Api(ApiError.Network)),
            fx.manager.state.value,
        )

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Api(ApiError.Network)),
            fx.manager.state.value,
        )
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(3, 4.seconds, ConnectionError.Api(ApiError.Network)),
            fx.manager.state.value,
        )
        advanceTimeBy(4.seconds + 1.milliseconds)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Network)), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)
        advanceTimeBy(60.seconds)
        assertEquals(4, fx.connector.attempts.size, "no attempt after the limit")
        fx.assertNothingOpen()
    }

    @Test
    fun `a connection whose stream opened starts the attempts over after it drops`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.clients.refuseEvents = ApiError.Network

        fx.latestEvents().drop()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.ServerLost), fx.manager.state.value)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        // That reconnect's stream never opened, so the count goes on.
        assertEquals(
            ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Api(ApiError.Network)),
            fx.manager.state.value,
        )

        fx.clients.refuseEvents = null
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        fx.latestEvents().drop()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.ServerLost), fx.manager.state.value)
    }

    @Test
    fun `a server drop while SSH stands reconnects with a new client`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)

        fx.latestEvents().drop()
        runCurrent()

        assertReconnectsAfterServerDrop(fx, first)
    }

    @Test
    fun `an event stream that ends reconnects with a new client`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)

        fx.latestEvents().end()
        runCurrent()

        assertReconnectsAfterServerDrop(fx, first)
    }

    @Test
    fun `a dead server the app started is started again`() = runTest {
        val fx = Fixture(this)
        fx.tools.server = ServerLookup.NotRunning()
        val first = connected(fx)
        assertEquals(1, fx.tools.startedWith.size)

        fx.latestEvents().drop()
        runCurrent()

        assertReconnectsAfterServerDrop(fx, first)
        assertEquals(2, fx.tools.startedWith.size)
        val restarted = fx.clients.made.last()
        assertEquals("http://127.0.0.1:4100", restarted.baseUrl)
        assertEquals(fx.tools.startedWith.last().second, restarted.password)
    }

    private val destructive = Regex("""\b(kill|pkill|killall|reboot|shutdown|poweroff|halt|systemctl|init)\b""")

    private fun Fixture.assertNothingStoppedOrKilled() {
        val commands = connector.sessions.flatMap { it.exec.executions }.map { it.command }
        assertTrue(commands.none { destructive.containsMatchIn(it) }, "a stop, kill or reboot was sent: $commands")
    }

    @Test
    fun `a server that runs but does not answer is retried, never stopped, killed or replaced`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        // Discovery keeps finding the server, but it no longer answers through the tunnel.
        repeat(3) { fx.clients.failures += ApiError.Network }

        fx.latestEvents().drop()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Api(ApiError.Network)),
            fx.manager.state.value,
        )
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Network)), fx.manager.state.value)
        assertEquals(4, fx.tools.findServerCalls)
        assertTrue(fx.tools.startedWith.isEmpty(), "a running server is never replaced")
        fx.assertNothingStoppedOrKilled()
        fx.assertNothingOpen()
    }

    @Test
    fun `a live server whose port does not answer after a stream drop is never started next to`() = runTest {
        val fx = Fixture(this)
        // The app started the first server, so a start would be possible.
        fx.tools.server = ServerLookup.NotRunning()
        connected(fx)
        assertEquals(1, fx.tools.startedWith.size)
        // The process lives and holds its loopback port, but the port no longer answers.
        fx.tools.server = ServerLookup.Unresponsive("127.0.0.1", 4100, 200, ServerSource.StateFile)

        fx.latestEvents().drop()
        runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.ServerLost), fx.manager.state.value)
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.ServerUnresponsive),
            fx.manager.state.value,
        )
        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(3, 4.seconds, ConnectionError.ServerUnresponsive),
            fx.manager.state.value,
        )
        advanceTimeBy(4.seconds + 1.milliseconds)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.ServerUnresponsive), fx.manager.state.value)
        assertEquals(1, fx.tools.startedWith.size, "no second server was started")
        assertEquals(4, fx.tools.findServerCalls)
        fx.assertNothingStoppedOrKilled()
        fx.assertNothingOpen()
    }

    @Test
    fun `a server found gone after a drop is only started, nothing is stopped or killed`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        fx.tools.server = ServerLookup.NotRunning()

        fx.latestEvents().drop()
        runCurrent()

        assertReconnectsAfterServerDrop(fx, first)
        assertEquals(1, fx.tools.startedWith.size, "started once, as a first connect would")
        fx.assertNothingStoppedOrKilled()
    }

    @Test
    fun `an event stream failure retrying cannot help ends in an error`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.latestEvents().fail(ApiError.Unauthorized)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)
        fx.assertNothingOpen()
        advanceTimeBy(60.seconds)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `a watch that fails with another exception ends in Unexpected with that exception as its cause`() = runTest {
        val fx = Fixture(this)
        val failure = IllegalStateException("watch broke at " + HOST)
        fx.clients.breakEvents = { failure }
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        val error = assertIs<ConnectionState.Error>(fx.manager.state.value).error
        assertEquals(ConnectionError.Unexpected(), error)
        assertSame(failure, assertIs<ConnectionError.Unexpected>(error).cause)
        fx.assertNothingOpen()
    }

    @Test
    fun `a second server drop after a reconnect starts the attempts over`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        fx.latestEvents().drop()
        runCurrent()
        assertReconnectsAfterServerDrop(fx, first)

        fx.latestEvents().end()
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.ServerLost), fx.manager.state.value)
    }

    @Test
    fun `an SSH drop that also breaks the event stream counts as an SSH drop`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.latestEvents().drop()
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1, 1.seconds, ConnectionError.Disconnected), fx.manager.state.value)
    }

    @Test
    fun `disconnect is not taken for a server drop`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.disconnect()
        runCurrent()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        fx.assertNoReconnectAttempted()
        fx.assertNothingOpen()
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `close is not taken for a server drop`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.close()
        runCurrent()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        fx.assertNoReconnectAttempted()
        fx.assertNothingOpen()
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `closing while waiting after a server drop leaves nothing open`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.latestEvents().drop()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)

        fx.manager.close()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
        fx.assertNothingOpen()
    }

    @Test
    fun `closing after reconnecting from a server drop or a stream end leaves nothing open`() = runTest {
        for (breakStream in listOf<(FakeEventApi) -> Unit>({ it.drop() }, { it.end() })) {
            val fx = Fixture(this)
            fx.tools.server = ServerLookup.NotRunning()
            connected(fx)
            breakStream(fx.latestEvents())
            runCurrent()
            advanceTimeBy(1.seconds + 1.milliseconds)
            runCurrent()
            assertIs<ConnectionState.Connected>(fx.manager.state.value)

            fx.manager.close()

            assertEquals(ConnectionState.Closed, fx.manager.state.value)
            fx.assertNothingOpen()
        }
    }

    @Test
    fun `closing after an event stream failure leaves nothing open`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.latestEvents().fail(ApiError.Unauthorized)
        runCurrent()

        fx.manager.close()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        fx.assertNothingOpen()
    }

    @Test
    fun `walks through SSH, host approval, server and API to connected`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()

        fx.manager.connect(profile())
        runCurrent()

        val approval = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
        assertEquals(SERVER_KEY, approval.key)
        assertEquals(0, fx.connector.credentialsReceived, "no credential before the key is approved")

        assertTrue(fx.manager.approveHostKey(SERVER_KEY))
        runCurrent()

        val connected = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(
            listOf("Idle", "Ssh", "HostApproval", "Ssh", "Server", "Api", "Connected"),
            fx.states.map { it.kind() },
        )
        assertEquals(
            HostKeyFingerprint(SERVER_KEY.fingerprintSha256),
            fx.knownHosts.approved(HostEndpoint(HOST, SSH_PORT)),
        )
        val made = fx.clients.made.single()
        assertEquals("http://127.0.0.1:4096", made.baseUrl)
        assertEquals(OPENCODE_PASSWORD, made.password)
        assertSame(fx.connector.sessions.single().socketFactory(), made.sockets, "the API goes through the tunnel")
        assertSame(made.client, connected.client)
        assertEquals("2.2.5", connected.info.version)
        assertEquals(1L, connected.generation)
    }

    @Test
    fun `a known host key is not asked about`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        assertEquals(listOf("Idle", "Ssh", "Server", "Api", "Connected"), fx.states.map { it.kind() })
    }

    @Test
    fun `a rejected host key ends in an error without sending a credential`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()

        assertTrue(fx.manager.rejectHostKey(SERVER_KEY))
        runCurrent()

        val error = assertIs<ConnectionState.Error>(fx.manager.state.value).error
        assertEquals(ConnectionError.HostKeyRejected(SERVER_KEY), error)
        assertEquals(0, fx.connector.credentialsReceived)
        assertNull(fx.knownHosts.approved(HostEndpoint(HOST, SSH_PORT)))
        fx.assertNothingOpen()
    }

    @Test
    fun `a decision about another key does nothing`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()

        assertFalse(fx.manager.approveHostKey(OTHER_KEY))
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.manager.state.value)
    }

    @Test
    fun `a changed host key is refused without asking`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        val approved = HostKeyFingerprint(OTHER_KEY.fingerprintSha256)
        fx.knownHosts.approve(HostEndpoint(HOST, SSH_PORT), approved)

        fx.manager.connect(profile())
        runCurrent()

        val error = assertIs<ConnectionState.Error>(fx.manager.state.value).error
        assertEquals(
            ConnectionError.HostKeyChanged(SERVER_KEY, approved, ConnectionServer(profile().id, HostEndpoint(HOST, SSH_PORT))),
            error,
        )
        assertFalse(fx.states.any { it is ConnectionState.HostApproval })
        assertEquals(0, fx.connector.credentialsReceived)
        assertEquals(approved, fx.knownHosts.approved(HostEndpoint(HOST, SSH_PORT)))
    }

    @Test
    fun `a refused login is an error`() = runTest {
        val fx = Fixture(this, acceptsCredential = { false })
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.AuthenticationFailed), fx.manager.state.value)
        fx.assertNothingOpen()
    }

    @Test
    fun `logs in with a private key and its passphrase`() = runTest {
        val keyText = "test-" + "key-" + "text"
        val passphrase = "test-" + "pass" + "phrase"
        val fx = Fixture(this, acceptsCredential = {
            it is SshCredential.PrivateKey && String(it.privateKey) == keyText && it.passphrase?.let(::String) == passphrase
        })
        val keyRef = SecretRef("key")
        val passRef = SecretRef("passphrase")
        SecretValue.ofText(keyText).use { fx.secrets.put(keyRef, it) }
        SecretValue.ofText(passphrase).use { fx.secrets.put(passRef, it) }
        SecretValue.ofText(OPENCODE_PASSWORD).use { fx.secrets.put(OPENCODE_REF, it) }
        fx.trustServerKey()

        fx.manager.connect(profile(auth = SshAuth.PrivateKey(keyRef, passRef)))
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `a missing SSH secret is an error before any connection`() = runTest {
        val fx = Fixture(this)
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.SecretMissing), fx.manager.state.value)
        assertTrue(fx.connector.attempts.isEmpty())
    }

    @Test
    fun `an unreadable SSH secret is its own error`() = runTest {
        val fx = Fixture(this)
        fx.secrets.seedUnreadable(SSH_SECRET_REF)
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.SecretUnreadable), fx.manager.state.value)
    }

    @Test
    fun `an unreachable server on the first connect is an error, not a retry`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.connector.failNext(SshException.ConnectionFailed())
        fx.manager.connect(profile())
        runCurrent()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `a server that is too old is refused and released`() = runTest {
        val fx = Fixture(this)
        fx.clients.version = "2.1.9"
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        val error = assertIs<ConnectionState.Error>(fx.manager.state.value).error
        assertIs<ApiError.VersionTooOld>(assertIs<ConnectionError.Api>(error).error)
        fx.assertNothingOpen()
    }

    @Test
    fun `a wrong opencode password is an error`() = runTest {
        val fx = Fixture(this)
        fx.clients.failures += ApiError.Unauthorized
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)
        fx.assertNothingOpen()
    }

    @Test
    fun `starts the server when none runs`() = runTest {
        val fx = Fixture(this)
        fx.tools.server = ServerLookup.NotRunning()
        val connected = connected(fx)

        assertEquals("/opt/opencode/bin/opencode", fx.tools.startedWith.single().first.binary)
        val made = fx.clients.made.single()
        assertEquals("http://127.0.0.1:4100", made.baseUrl)
        assertEquals(fx.tools.startedWith.single().second, made.password)
        assertSame(made.client, connected.client)
    }

    @Test
    fun `reconnects after a drop with a new session and client`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        val firstSession = fx.connector.sessions.single()

        firstSession.loseConnection()
        runCurrent()

        val waiting = assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertEquals(1, waiting.attempt)
        assertEquals(1.seconds, waiting.retryIn)
        assertEquals(ConnectionError.Disconnected, waiting.cause)
        assertTrue(fx.clients.made.single().client.control.isClosed, "the old client is closed at once")

        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()

        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.sessions.size)
        assertEquals(2, fx.clients.made.size)
        assertSame(fx.clients.made.last().client, second.client)
        assertEquals(first.generation + 1, second.generation)
        assertEquals(
            listOf("Reconnecting", "Ssh", "Server", "Api", "Connected"),
            fx.states.takeLast(5).map { it.kind() },
        )
        assertEquals(ConnectionState.Ssh(reconnectAttempt = 1), fx.states.takeLast(4).first())
    }

    @Test
    fun `retries a failing reconnect with growing delays`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.connector.failNext(SshException.ConnectionFailed())
        fx.connector.failNext(SshException.Timeout())

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(2, 2.seconds, ConnectionError.Unreachable),
            fx.manager.state.value,
        )

        advanceTimeBy(2.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(
            ConnectionState.Reconnecting(3, 4.seconds, ConnectionError.Timeout),
            fx.manager.state.value,
        )

        advanceTimeBy(4.seconds + 1.milliseconds)
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `gives up after the last attempt`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        repeat(3) { fx.connector.failNext(SshException.ConnectionFailed()) }

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertEquals(4, fx.connector.attempts.size)
        fx.assertNothingOpen()
    }

    @Test
    fun `a lasting failure during a reconnect is not retried`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.clients.failures += ApiError.Unauthorized

        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)
        advanceTimeBy(60.seconds)
        assertEquals(2, fx.connector.attempts.size)
    }

    @Test
    fun `reconnect skips the wait and drops a live connection`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertFalse(fx.connector.sessions.first().connected.value)

        fx.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(2, fx.connector.sessions.size)
    }

    /** Records every SFTP open, by session, before it reaches the session. */
    private class RecordingSftpConnector(private val real: SshConnector, private val opened: MutableList<SshSession>) :
        SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            val inner = real.connect(target, credential, hostKeyVerifier, options)
            return object : SshSession by inner {
                override suspend fun openSftp(root: String): SftpClient {
                    synchronized(opened) { opened += inner }
                    return inner.openSftp(root)
                }
            }
        }
    }

    private suspend fun Fixture.addOtherServer() {
        connector.addServer(OTHER_TARGET)
        knownHosts.approve(HostEndpoint(OTHER_HOST, OTHER_PORT), HostKeyFingerprint(SERVER_KEY.fingerprintSha256))
    }

    @Test
    fun `withConnection fails when no connection stands and never runs the block`() = runTest {
        val fx = Fixture(this)
        var ran = false

        assertFailsWith<NotConnectedException> { fx.manager.withConnection { ran = true } }
        connected(fx)
        fx.manager.disconnect()
        assertFailsWith<NotConnectedException> { fx.manager.withConnection { ran = true } }

        assertFalse(ran)
    }

    @Test
    fun `withConnection hands the block the connected session's exec, client fs, server and generation`() = runTest {
        val fx = Fixture(this)
        val connected = connected(fx)
        val session = fx.connector.sessions.single()
        session.exec.on("echo ok") { stdout("ok\n"); exit(0) }

        val result = fx.manager.withConnection { bound ->
            assertSame(session.exec, bound.exec)
            assertSame(fx.clients.made.single().client.fs, bound.fs)
            assertEquals(connected.server, bound.server)
            assertEquals(connected.generation, bound.generation)
            bound.exec.run(ExecRequest("echo ok"))
        }

        assertEquals("ok\n", result.stdout.decodeToString())
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    @Test
    fun `withConnection opens SFTP on the bound session, and the connection owns the client`() = runTest {
        val opened = mutableListOf<SshSession>()
        val fx = Fixture(this, wrapConnector = { RecordingSftpConnector(it, opened) })
        connected(fx)
        val session = fx.connector.sessions.single()
        session.files.addDirectory("/srv/project")

        val client = fx.manager.withConnection { bound -> bound.openSftp("/srv/project") }

        assertEquals("/srv/project", client.root)
        assertEquals(listOf<SshSession>(session), opened)
        session.loseConnection()
        runCurrent()
        assertFailsWith<SshException> { client.list("") }
    }

    @Test
    fun `withConnection fails when the connection drops during the block`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        val started = CompletableDeferred<Unit>()
        var cancelled = false

        val call = async {
            runCatching {
                fx.manager.withConnection {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (cancel: CancellationException) {
                        cancelled = true
                        throw cancel
                    }
                }
            }
        }
        runCurrent()
        assertTrue(started.isCompleted)
        fx.connector.sessions.single().loseConnection()
        runCurrent()

        assertIs<NotConnectedException>(call.await().exceptionOrNull())
        assertTrue(cancelled, "the block is cancelled")
    }

    @Test
    fun `withConnection fails when the block outlives its connection, even if it returns`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        val release = CompletableDeferred<Unit>()

        val call = async {
            runCatching { fx.manager.withConnection { withContext(NonCancellable) { release.await() }; "done" } }
        }
        runCurrent()
        fx.manager.reconnect()
        runCurrent()
        fx.manager.reconnect()
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        release.complete(Unit)
        runCurrent()

        assertIs<NotConnectedException>(call.await().exceptionOrNull())
    }

    @Test
    fun `withConnection never moves to a newer connection when the generation changes`() = runTest {
        val fx = Fixture(this)
        val first = connected(fx)
        val firstSession = fx.connector.sessions.single()
        val seen = mutableListOf<RemoteExec>()

        val call = async {
            runCatching { fx.manager.withConnection { bound -> seen += bound.exec; awaitCancellation() } }
        }
        runCurrent()
        fx.manager.connect(profile())
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertTrue(second.generation > first.generation)

        assertIs<NotConnectedException>(call.await().exceptionOrNull())
        assertEquals(listOf<RemoteExec>(firstSession.exec), seen)
        assertSame(fx.connector.sessions.last().exec, fx.manager.withConnection { it.exec })
    }

    @Test
    fun `a server connected after the bind is never reached through it`() = runTest {
        val opened = mutableListOf<SshSession>()
        val fx = Fixture(this, wrapConnector = { RecordingSftpConnector(it, opened) })
        val first = connected(fx)
        fx.addOtherServer()
        val bound = CompletableDeferred<BoundConnection>()
        val proceed = CompletableDeferred<Unit>()
        var openFailure: Throwable? = null

        val call = async {
            runCatching {
                fx.manager.withConnection { connection ->
                    bound.complete(connection)
                    // Goes on past the drop's cancellation, as code between two checks would.
                    withContext(NonCancellable) {
                        proceed.await()
                        openFailure = runCatching { connection.openSftp("/srv/project") }.exceptionOrNull()
                    }
                }
            }
        }
        runCurrent()
        fx.manager.connect(otherProfile())
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(otherProfile().id, second.server.profileId)
        fx.connector.sessions.last().files.addDirectory("/srv/project")
        proceed.complete(Unit)
        runCurrent()

        assertIs<NotConnectedException>(openFailure)
        assertIs<NotConnectedException>(call.await().exceptionOrNull())
        assertTrue(opened.isEmpty(), "no SFTP channel opened on any session, the new server's included")
        val connection = bound.await()
        assertEquals(first.server, connection.server)
        assertEquals(first.generation, connection.generation)
        assertSame(fx.clients.made.first().client.fs, connection.fs)
        assertNotSame(fx.clients.made.last().client.fs, connection.fs)
        assertTrue(fx.clients.made.last().client.control.calls.none { it.startsWith("fs.") }, "the new server's API is untouched")
    }

    @Test
    fun `withConnection propagates the block's own failure while the connection stands`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        assertFailsWith<SshException.Timeout> {
            fx.manager.withConnection { throw SshException.Timeout() }
        }
    }

    @Test
    fun `closing leaves nothing open`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.close()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        fx.assertNothingOpen()
        runCurrent()
        advanceTimeBy(60.seconds)
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size, "nothing reconnects after closing")
        fx.manager.close()
    }

    @Test
    fun `closing while the host key waits for the user leaves nothing open`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        fx.manager.close()
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertFalse(fx.manager.approveHostKey(SERVER_KEY))
        assertNull(fx.knownHosts.approved(HostEndpoint(HOST, SSH_PORT)))
        fx.assertNothingOpen()
    }

    @Test
    fun `closing during server discovery closes the session`() = runTest {
        val fx = Fixture(this)
        fx.tools.gate = CompletableDeferred()
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Server(), fx.manager.state.value)

        fx.manager.close()

        assertFalse(fx.connector.sessions.single().connected.value)
        runCurrent()
        fx.assertNothingOpen()
        assertTrue(fx.clients.made.isEmpty())
    }

    @Test
    fun `closing while waiting to reconnect stops for good`() = runTest {
        val fx = Fixture(this)
        connected(fx)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)

        fx.manager.close()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
        fx.assertNothingOpen()
    }

    @Test
    fun `connect after close is refused`() = runTest {
        val fx = Fixture(this)
        fx.manager.close()
        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertTrue(fx.connector.attempts.isEmpty())
    }

    @Test
    fun `disconnect releases everything and returns to idle`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.disconnect()
        runCurrent()

        assertEquals(ConnectionState.Idle, fx.manager.state.value)
        fx.assertNothingOpen()
        advanceTimeBy(60.seconds)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `a new connect replaces the current connection`() = runTest {
        val fx = Fixture(this)
        connected(fx)

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertFalse(fx.connector.sessions.first().connected.value)
        assertTrue(fx.clients.made.first().client.control.isClosed)
        assertTrue(fx.connector.sessions.last().connected.value)
    }

    @Test
    fun `an approval given after the server dropped the handshake connects again at once`() = runTest {
        val fx = Fixture(this, wrapConnector = { real -> DropsFirstHandshake(real) })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        fx.manager.approveHostKey(SERVER_KEY)
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size, "the second connect is the real one")
    }

    @Test
    fun `a dropped handshake without a new approval is an error`() = runTest {
        val fx = Fixture(this, wrapConnector = { real -> DropsFirstHandshake(real) })
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
    }

    @Test
    fun `closing as the connect begins leaves no attempt running`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.first { it is ConnectionState.Ssh }
            fx.manager.close()
        }
        runCurrent()

        fx.manager.connect(profile())
        runCurrent()
        advanceTimeBy(60.seconds)

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertTrue(fx.connector.attempts.isEmpty(), "no SSH attempt after close")
        fx.assertNothingOpen()
    }

    @Test
    fun `a reconnect asked for as soon as the connection stands is not lost`() = runTest {
        val fx = Fixture(this)
        fx.seedSecrets()
        fx.trustServerKey()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.first { it is ConnectionState.Connected }
            fx.manager.reconnect()
        }
        runCurrent()

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        assertFalse(fx.connector.sessions.single().connected.value)
    }

    @Test
    fun `a server started for a profile with a credential is reached again with the started password`() = runTest {
        val fx = Fixture(this)
        fx.tools.server = ServerLookup.NotRunning()
        fx.clients.serverPassword = { fx.tools.startedWith.singleOrNull()?.second }
        connected(fx)
        val started = fx.tools.startedWith.single().second

        fx.tools.server = ServerLookup.Running("127.0.0.1", 4100, 200, ServerSource.StateFile)
        fx.connector.sessions.single().loseConnection()
        runCurrent()
        advanceTimeBy(1.seconds + 1.milliseconds)
        runCurrent()

        assertIs<ConnectionState.Connected>(fx.manager.state.value)
        assertEquals(1, fx.tools.startedWith.size, "no second server")
        assertEquals(started, fx.clients.made.last().password)
    }

    @Test
    fun `a refused password falls back to the other known one and closes the refused client`() = runTest {
        val fx = Fixture(this)
        val started = "test-" + "started"
        SecretValue.ofText(started).use { fx.secrets.put(ServerResolver.startedPasswordRef(profile()), it) }
        fx.clients.serverPassword = { started }
        val connected = connected(fx)

        assertEquals(listOf(OPENCODE_PASSWORD, started), fx.clients.made.map { it.password })
        assertTrue(fx.clients.made.first().client.control.isClosed)
        assertSame(fx.clients.made.last().client, connected.client)
    }

    @Test
    fun `a password every candidate fails is an authorization error`() = runTest {
        val fx = Fixture(this)
        fx.clients.serverPassword = { "test-" + "other" }
        fx.seedSecrets()
        fx.trustServerKey()
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Api(ApiError.Unauthorized)), fx.manager.state.value)
        fx.assertNothingOpen()
    }

    @Test
    fun `cancelling the manager's scope closes the manager`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val fx = Fixture(this, managerScope = managerScope)
        connected(fx)

        managerScope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        fx.assertNothingOpen()
        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertEquals(1, fx.connector.attempts.size)
    }

    @Test
    fun `connect on a scope that already ended closes the manager`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        managerScope.cancel()
        val fx = Fixture(this, managerScope = managerScope)
        fx.seedSecrets()

        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        assertTrue(fx.connector.attempts.isEmpty())
    }

    @Test
    fun `close waits for a session the connection is still closing`() = runTest {
        val managerScope = CoroutineScope(Dispatchers.Default + Job())
        val gated = mutableListOf<GatedSession>()
        val fx = Fixture(
            this,
            wrapConnector = { real -> GatingConnector(real, gated) },
            managerScope = managerScope,
        )
        try {
            fx.seedSecrets()
            fx.trustServerKey()
            fx.manager.connect(profile())
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) { fx.manager.state.first { it is ConnectionState.Connected } }
            }
            val session = synchronized(gated) { gated.single() }

            // The drop makes the connection close its session; that close is held open.
            (session.delegate as FakeSshSession).loseConnection()
            assertTrue(session.closeStarted.await(10, TimeUnit.SECONDS))

            val closer = thread { runBlocking { fx.manager.close() } }
            closer.join(500)
            if (!closer.isAlive) assertTrue(session.closeFinished, "close returned while the session was still open")

            session.proceed.countDown()
            closer.join(10_000)
            assertFalse(closer.isAlive)
            assertTrue(session.closeFinished)
            assertEquals(ConnectionState.Closed, fx.manager.state.value)
        } finally {
            synchronized(gated) { gated.forEach { it.proceed.countDown() } }
            managerScope.cancel()
        }
    }

    @Test
    fun `close returns only after a cancelled SSH attempt has cleaned up and wiped its credential`() = runTest {
        val slow = SlowCleanupConnector()
        val fx = Fixture(this, wrapConnector = { slow })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()
        assertTrue(slow.entered)

        fx.manager.close()

        assertTrue(slow.cleanedUp, "close returned before the attempt finished")
        val password = assertIs<SshCredential.Password>(slow.credential).password
        assertTrue(password.all { it == '\u0000' }, "the credential is wiped")
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
    }

    @Test
    fun `disconnect returns only after the attempt has cleaned up`() = runTest {
        val slow = SlowCleanupConnector()
        val fx = Fixture(this, wrapConnector = { slow })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()

        fx.manager.disconnect()

        assertTrue(slow.cleanedUp)
        assertEquals(ConnectionState.Idle, fx.manager.state.value)
    }

    @Test
    fun `a second close waits for the first to finish`() = runTest {
        val slow = SlowCleanupConnector()
        val fx = Fixture(this, wrapConnector = { slow })
        fx.seedSecrets()
        fx.manager.connect(profile())
        runCurrent()

        val first = async { fx.manager.close() }
        val second = async { fx.manager.close() }
        runCurrent()

        assertFalse(second.isCompleted, "the second close returned while the first was still releasing")
        assertFalse(first.isCompleted)
        // The manager runs in the background scope, which advanceUntilIdle leaves alone.
        first.await()
        second.await()
        assertTrue(slow.cleanedUp)
    }

    @Test
    fun `cancelling the scope of an idle manager closes it`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val fx = Fixture(this, managerScope = managerScope)

        managerScope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
    }

    @Test
    fun `cancelling the scope after a failed attempt closes the manager`() = runTest {
        val managerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val fx = Fixture(this, managerScope = managerScope)
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Error>(fx.manager.state.value)

        managerScope.cancel()
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        fx.manager.connect(profile())
        runCurrent()
        assertEquals(ConnectionState.Closed, fx.manager.state.value)
    }

    /** Hangs in connect until cancelled, then takes a second to clean up, as a real handshake may. */
    private class SlowCleanupConnector : SshConnector {
        @Volatile
        var entered = false

        @Volatile
        var cleanedUp = false

        @Volatile
        var credential: SshCredential? = null

        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            this.credential = credential
            entered = true
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(1.seconds) }
                cleanedUp = true
            }
        }
    }

    /** A session whose first close blocks until [proceed]. */
    private class GatedSession(val delegate: SshSession) : SshSession by delegate {
        val closeStarted = CountDownLatch(1)
        val proceed = CountDownLatch(1)

        @Volatile
        var closeFinished = false

        override fun close() {
            closeStarted.countDown()
            proceed.await(10, TimeUnit.SECONDS)
            delegate.close()
            closeFinished = true
        }
    }

    private class GatingConnector(private val real: SshConnector, private val made: MutableList<GatedSession>) : SshConnector {
        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession = GatedSession(real.connect(target, credential, hostKeyVerifier, options))
            .also { synchronized(made) { made += it } }
    }

    /** Asks the verifier like a server would, then loses the connection: a user who took too long. */
    private class DropsFirstHandshake(private val real: SshConnector) : SshConnector {
        private var dropped = false

        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            if (!dropped) {
                dropped = true
                hostKeyVerifier.verify(target, SERVER_KEY)
                throw SshException.ConnectionFailed()
            }
            return real.connect(target, credential, hostKeyVerifier, options)
        }
    }
}
