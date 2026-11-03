package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

// A blocked sshj thread ignores coroutine cancellation; this ends such a test instead of the build.
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshjConnectorTest {
    private val connector = SshjConnector()
    private val closeables = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun server(
        hostKeyPair: java.security.KeyPair = TestKeys.ed25519(),
        authorizedKeys: List<java.security.PublicKey> = emptyList(),
    ) = TestSshServer(hostKeyPair, authorizedKeys).closeLater()

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(TEST_TIMEOUT) { body() } }

    private suspend fun connect(
        target: SshTarget,
        credential: SshCredential,
        verifier: HostKeyVerifier = ACCEPT,
        options: SshConnectOptions = SshConnectOptions(),
    ): SshSession = connector.connect(target, credential, verifier, options).closeLater()

    // Login

    @Test
    fun `connects with a password`() = test {
        val server = server()
        val session = connect(server.target, password())
        assertTrue(session.connected.value)
        assertEquals(1, server.openSessions())
    }

    @Test
    fun `connects with an ed25519 key`() = test {
        val key = TestKeys.ed25519()
        val server = server(authorizedKeys = listOf(key.public))
        assertTrue(connect(server.target, privateKey(key)).connected.value)
    }

    @Test
    fun `connects with an RSA key`() = test {
        val key = TestKeys.rsa()
        val server = server(authorizedKeys = listOf(key.public))
        assertTrue(connect(server.target, privateKey(key)).connected.value)
    }

    @Test
    fun `connects with a passphrase-protected key`() = test {
        val key = TestKeys.ed25519()
        val server = server(authorizedKeys = listOf(key.public))
        assertTrue(connect(server.target, privateKey(key, TestSecrets.PASSPHRASE)).connected.value)
    }

    @Test
    fun `connects to a server with an RSA host key`() = test {
        val server = server(hostKeyPair = TestKeys.rsa())
        var seen: HostKey? = null
        connect(server.target, password(), HostKeyVerifier { _, key -> seen = key; HostKeyDecision.Accept })
        assertEquals(server.hostKey, seen)
    }

    @Test
    fun `a wrong password fails with AuthenticationFailed and leaves no connection`() = test {
        val server = server()
        assertFailsWith<SshException.AuthenticationFailed> {
            connect(server.target, password(TestSecrets.WRONG_PASSWORD))
        }
        eventually("server sessions closed") { server.openSessions() == 0 }
    }

    @Test
    fun `a key the server does not know fails with AuthenticationFailed`() = test {
        val server = server(authorizedKeys = listOf(TestKeys.ed25519().public))
        assertFailsWith<SshException.AuthenticationFailed> { connect(server.target, privateKey(TestKeys.ed25519())) }
        eventually("server sessions closed") { server.openSessions() == 0 }
    }

    @Test
    fun `a wrong passphrase fails with AuthenticationFailed before any network work`() = test {
        val key = TestKeys.ed25519()
        val server = server(authorizedKeys = listOf(key.public))
        val credential = SshCredential.PrivateKey(
            TestKeys.openSsh(key, TestSecrets.PASSPHRASE).toCharArray(),
            TestSecrets.WRONG_PASSPHRASE.toCharArray(),
        )
        var asked = false
        assertFailsWith<SshException.AuthenticationFailed> {
            connect(server.target, credential, HostKeyVerifier { _, _ -> asked = true; HostKeyDecision.Accept })
        }
        assertFalse(asked, "No connection should be made for an unreadable key")
        assertEquals(0, server.credentialsReceived())
    }

    @Test
    fun `a malformed key fails with AuthenticationFailed`() = test {
        val server = server()
        val credential = SshCredential.PrivateKey("not a key".toCharArray())
        assertFailsWith<SshException.AuthenticationFailed> { connect(server.target, credential) }
        assertEquals(0, server.credentialsReceived())
    }

    @Test
    fun `connect leaves the caller's arrays as they were, and the session survives wiping them`() = test {
        val key = TestKeys.ed25519()
        val server = server(authorizedKeys = listOf(key.public))
        val keyText = TestKeys.openSsh(key, TestSecrets.PASSPHRASE)
        val credential = SshCredential.PrivateKey(keyText.toCharArray(), TestSecrets.PASSPHRASE.toCharArray())
        val session = connect(server.target, credential)
        assertContentEquals(keyText.toCharArray(), credential.privateKey)
        assertContentEquals(TestSecrets.PASSPHRASE.toCharArray(), credential.passphrase)
        credential.wipe()
        assertTrue(session.connected.value)

        val pass = password()
        val second = connect(server.target, pass)
        assertContentEquals(TestSecrets.PASSWORD.toCharArray(), pass.password)
        pass.wipe()
        assertTrue(second.connected.value)
    }

    // Host keys

    @Test
    fun `the verifier sees the target and the key the server presented`() = test {
        val server = server()
        var seen: Pair<SshTarget, HostKey>? = null
        connect(server.target, password(), HostKeyVerifier { target, key -> seen = target to key; HostKeyDecision.Accept })
        assertEquals(server.target to server.hostKey, seen)
    }

    @Test
    fun `a rejected key fails with HostKeyRejected and sends neither password nor key`() = test {
        val key = TestKeys.ed25519()
        val server = server(authorizedKeys = listOf(key.public))
        listOf(password(), privateKey(key)).forEach { credential ->
            val failure = assertFailsWith<SshException.HostKeyRejected> { connect(server.target, credential, REJECT) }
            assertEquals(server.hostKey, failure.hostKey)
        }
        assertEquals(0, server.credentialsReceived())
        eventually("server sessions closed") { server.openSessions() == 0 }
    }

    @Test
    fun `a changed host key is rejected under the known-hosts policy`() = test {
        val server = server()
        val known = KnownHostsPolicy()
        known.store(server.target, TestKeys.hostKeyOf(TestKeys.ed25519().public))
        val failure = assertFailsWith<SshException.HostKeyRejected> { connect(server.target, password(), known) }
        assertEquals(server.hostKey, failure.hostKey)
        assertEquals(0, server.credentialsReceived())
        assertEquals(0, known.asked)
    }

    @Test
    fun `a known host key is accepted under the known-hosts policy`() = test {
        val server = server()
        val known = KnownHostsPolicy()
        known.store(server.target, server.hostKey)
        assertTrue(connect(server.target, password(), known).connected.value)
    }

    @Test
    fun `a verifier that throws counts as a rejection`() = test {
        val server = server()
        assertFailsWith<SshException.HostKeyRejected> {
            connect(server.target, password(), HostKeyVerifier { _, _ -> error("verifier failed") })
        }
        assertEquals(0, server.credentialsReceived())
    }

    @Test
    fun `no credential is sent while the verifier is deciding`() = test {
        val server = server()
        val asked = CompletableDeferred<Unit>()
        val decide = CompletableDeferred<HostKeyDecision>()
        val verifier = HostKeyVerifier { _, _ ->
            asked.complete(Unit)
            decide.await()
        }
        withContext(Dispatchers.Default) {
            val connecting = async { connect(server.target, password(), verifier) }
            asked.await()
            delay(300.milliseconds)
            assertEquals(0, server.credentialsReceived())
            decide.complete(HostKeyDecision.Accept)
            assertTrue(connecting.await().connected.value)
        }
        assertEquals(1, server.credentialsReceived())
    }

    @Test
    fun `the key is checked again on every connect`() = test {
        val server = server()
        var checks = 0
        val verifier = HostKeyVerifier { _, _ ->
            checks++
            if (checks == 1) HostKeyDecision.Reject else HostKeyDecision.Accept
        }
        assertFailsWith<SshException.HostKeyRejected> { connect(server.target, password(), verifier) }
        connect(server.target, password(), verifier)
        assertEquals(2, checks)
    }

    // Timeouts

    @Test
    fun `time spent with the verifier does not count against the connect timeout`() = test {
        val server = server()
        val slow = HostKeyVerifier { _, _ ->
            delay(SHORT_TIMEOUT * 4)
            HostKeyDecision.Accept
        }
        assertTrue(connect(server.target, password(), slow, SshConnectOptions(connectTimeout = SHORT_TIMEOUT * 2)).connected.value)
    }

    @Test
    fun `timeouts run on real time even under a test dispatcher`() = runTest {
        val server = server()
        val slow = HostKeyVerifier { _, _ ->
            withContext(Dispatchers.Default) { delay(SHORT_TIMEOUT * 3) }
            HostKeyDecision.Accept
        }
        connect(server.target, password(), slow, SshConnectOptions(connectTimeout = SHORT_TIMEOUT * 2)).close()
    }

    @Test
    fun `a silent server fails with Timeout within the limit and leaves nothing open`() = test {
        val silent = SilentServer().closeLater()
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SshException.Timeout> {
            connect(silent.target, password(), ACCEPT, SshConnectOptions(connectTimeout = SHORT_TIMEOUT))
        }
        assertTrue(started.elapsedNow() < 3.seconds, "Took ${started.elapsedNow()}")
        assertEquals(1, silent.connectionsAccepted())
        eventually("client socket closed") { silent.openConnections() == 0 }
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    @Test
    fun `a server that stalls after the host key fails with Timeout and sends no credential`() = test {
        val server = server()
        val proxy = FreezableProxy(server.port).closeLater()
        val freezing = HostKeyVerifier { _, _ ->
            proxy.freeze()
            HostKeyDecision.Accept
        }
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SshException.Timeout> {
            connect(proxy.target(), password(), freezing, SshConnectOptions(connectTimeout = 500.milliseconds))
        }
        assertTrue(started.elapsedNow() < 3.seconds, "Took ${started.elapsedNow()}")
        assertEquals(0, server.credentialsReceived())
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    @Test
    fun `a port nothing listens on fails with ConnectionFailed`() = test {
        val target = SshTarget("127.0.0.1", unusedPort(), TestSecrets.USER)
        assertFailsWith<SshException.ConnectionFailed> { connect(target, password()) }
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    // Keep-alive

    @Test
    fun `keep-alive detects a server that stops responding`() = test {
        val server = server()
        val proxy = FreezableProxy(server.port).closeLater()
        val session = connect(
            proxy.target(),
            password(),
            options = SshConnectOptions(keepAliveInterval = 200.milliseconds, keepAliveMaxMissed = 2),
        )
        // Answered keep-alives keep an idle connection up.
        delay(1.seconds)
        assertTrue(session.connected.value, "An answering server must stay connected")
        proxy.freeze()
        val frozenAt = TimeSource.Monotonic.markNow()
        eventually("connection counted as lost") { !session.connected.value }
        assertTrue(frozenAt.elapsedNow() < 3.seconds, "Took ${frozenAt.elapsedNow()}")
        eventually("keep-alive stopped") { !(session as SshjSession).keepAliveRunning }
        eventually("a lost session releases its threads") { sshjThreads().isEmpty() }
    }

    @Test
    fun `without keep-alive a silent server is not noticed`() = test {
        val server = server()
        val proxy = FreezableProxy(server.port).closeLater()
        val session = connect(proxy.target(), password(), options = SshConnectOptions(keepAliveInterval = null))
        proxy.freeze()
        delay(1.seconds)
        assertTrue(session.connected.value)
    }

    @Test
    fun `connected turns false when the server drops the connection`() = test {
        val server = server()
        val session = connect(server.target, password())
        server.dropAll()
        eventually("connection lost") { !session.connected.value }
        eventually("a lost session releases its threads") { sshjThreads().isEmpty() }
    }

    // Closing and cancelling

    @Test
    fun `close is idempotent and releases threads and the server session`() = test {
        val server = server()
        val session = connect(
            server.target,
            password(),
            options = SshConnectOptions(keepAliveInterval = 100.milliseconds),
        ) as SshjSession
        assertTrue(sshjThreads().isNotEmpty(), "A connected session runs a reader thread")
        session.close()
        session.close()
        assertFalse(session.connected.value)
        assertFalse(session.keepAliveRunning, "Close must stop the keep-alive")
        assertFailsWith<SshException.Disconnected> { session.openSftp("/") }
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
        eventually("server session closed") { server.openSessions() == 0 }
    }

    @Test
    fun `close returns promptly while a write is blocked on a peer that stopped reading`() = test {
        val server = server()
        val proxy = FreezableProxy(server.port).closeLater()
        val session = connect(proxy.target(), password(), options = SshConnectOptions(keepAliveInterval = null)) as SshjSession
        proxy.freeze()

        // Fills the socket buffers until a write blocks; the frozen proxy reads nothing.
        val written = AtomicLong()
        val writer = thread(isDaemon = true, name = "test-filler") {
            runCatching {
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    session.client.connection.sendGlobalRequest("fill@test", false, chunk)
                    written.addAndGet(chunk.size.toLong())
                }
            }
        }
        var last = -1L
        eventually("a write blocks", within = 20.seconds) {
            val now = written.get()
            (now == last && now > 0).also { last = now; Thread.sleep(300) }
        }
        assertTrue(writer.isAlive, "The writer must be blocked, not failed")

        val closer = thread(isDaemon = true, name = "test-closer") { session.close() }
        closer.join(3_000)
        assertFalse(closer.isAlive, "close() must not hang on a blocked write")
        assertFalse(session.connected.value)
        writer.join(3_000)
        assertFalse(writer.isAlive, "The blocked write must be broken")
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    @Test
    fun `keep-alive detects a lost connection while a write is blocked`() = test {
        val server = server()
        val proxy = FreezableProxy(server.port).closeLater()
        val interval = 1500.milliseconds
        val maxMissed = 2
        val session = connect(
            proxy.target(),
            password(),
            options = SshConnectOptions(keepAliveInterval = interval, keepAliveMaxMissed = maxMissed),
        ) as SshjSession
        proxy.freeze()
        val frozenAt = TimeSource.Monotonic.markNow()

        // Holds sshj's write lock in a blocked write, so every keep-alive send blocks too.
        val written = AtomicLong()
        val writer = thread(isDaemon = true, name = "test-filler") {
            runCatching {
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    session.client.connection.sendGlobalRequest("fill@test", false, chunk)
                    written.addAndGet(chunk.size.toLong())
                }
            }
        }
        var last = -1L
        eventually("a write blocks", within = 20.seconds) {
            val now = written.get()
            (now == last && now > 0).also { last = now; Thread.sleep(200) }
        }
        assertTrue(writer.isAlive && session.connected.value, "Precondition: a write blocks before any detection")

        eventually("connection counted as lost", within = 20.seconds) { !session.connected.value }
        val limit = interval * (maxMissed + 1) + 1.seconds
        assertTrue(frozenAt.elapsedNow() < limit, "Took ${frozenAt.elapsedNow()}, limit $limit")
        eventually("keep-alive stopped") { !session.keepAliveRunning }
        writer.join(3_000)
        assertFalse(writer.isAlive, "The blocked write must be broken")
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    @Test
    fun `cancelling while the verifier decides closes the half-open connection`() = test {
        val server = server()
        val asked = CompletableDeferred<Unit>()
        val verifier = HostKeyVerifier { _, _ ->
            asked.complete(Unit)
            awaitCancellation()
        }
        withContext(Dispatchers.Default) {
            val connecting = async { connect(server.target, password(), verifier) }
            asked.await()
            connecting.cancel()
            assertFailsWith<CancellationException> { connecting.await() }
        }
        assertEquals(0, server.credentialsReceived())
        eventually("server sessions closed") { server.openSessions() == 0 }
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    @Test
    fun `cancelling a handshake with a silent server leaves nothing running`() = test {
        val silent = SilentServer().closeLater()
        withContext(Dispatchers.Default) {
            val connecting = async { connect(silent.target, password()) }
            eventually("connection accepted") { silent.connectionsAccepted() == 1 }
            connecting.cancel()
            assertFailsWith<CancellationException> { connecting.await() }
        }
        eventually("client socket closed") { silent.openConnections() == 0 }
        eventually("sshj threads ended") { sshjThreads().isEmpty() }
    }

    // Secrets

    @Test
    fun `secrets never reach exception text or toString`() = test {
        val key = TestKeys.ed25519()
        val keyText = TestKeys.openSsh(key, TestSecrets.PASSPHRASE)
        val server = server(authorizedKeys = listOf(TestKeys.ed25519().public))
        val failures = buildList {
            add(runCatching { connect(server.target, password(TestSecrets.WRONG_PASSWORD)) }.exceptionOrNull())
            add(runCatching { connect(server.target, privateKey(key, TestSecrets.PASSPHRASE)) }.exceptionOrNull())
            add(
                runCatching {
                    connect(server.target, SshCredential.PrivateKey(keyText.toCharArray(), TestSecrets.WRONG_PASSPHRASE.toCharArray()))
                }.exceptionOrNull(),
            )
            add(runCatching { connect(server.target, password(TestSecrets.WRONG_PASSWORD), REJECT) }.exceptionOrNull())
        }
        val good = server(authorizedKeys = listOf(key.public))
        val session = connect(good.target, privateKey(key, TestSecrets.PASSPHRASE))
        val texts = failures.map { checkNotNull(it).stackTraceToString() } + session.toString()
        val secrets = listOf(
            TestSecrets.WRONG_PASSWORD,
            TestSecrets.PASSPHRASE,
            TestSecrets.WRONG_PASSPHRASE,
            keyText.lines()[1],
            "127.0.0.1",
            TestSecrets.USER,
        )
        texts.forEach { text -> secrets.forEach { secret -> assertFalse(secret in text, "A secret leaked into:\n$text") } }
    }

    private companion object {
        val TEST_TIMEOUT = 30.seconds
        val SHORT_TIMEOUT = 200.milliseconds
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }
        val REJECT = HostKeyVerifier { _, _ -> HostKeyDecision.Reject }

        fun sshjThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("sshj-") }

        suspend fun eventually(what: String, within: Duration = 5.seconds, condition: () -> Boolean) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > within) throw AssertionError("Not within $within: $what")
                delay(20.milliseconds)
            }
        }
    }
}

/** The policy from [HostKeyVerifier]'s KDoc, over a map instead of the real known-hosts store. */
private class KnownHostsPolicy : HostKeyVerifier {
    private val known = ConcurrentHashMap<SshTarget, HostKey>()
    var asked = 0
        private set

    fun store(target: SshTarget, key: HostKey) {
        known[target] = key
    }

    override suspend fun verify(target: SshTarget, key: HostKey): HostKeyDecision {
        val stored = known[target]
        return when {
            stored == key -> HostKeyDecision.Accept
            stored != null -> HostKeyDecision.Reject
            else -> {
                asked++
                HostKeyDecision.Reject
            }
        }
    }
}
