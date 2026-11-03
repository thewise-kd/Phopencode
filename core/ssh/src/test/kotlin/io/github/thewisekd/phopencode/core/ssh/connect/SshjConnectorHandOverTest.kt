package io.github.thewisekd.phopencode.core.ssh.connect

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout

/** The edges of a connect: handing the session over, and name resolution. */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshjConnectorHandOverTest {
    private val closeables = mutableListOf<AutoCloseable>()
    private val lookupsMayEnd = CountDownLatch(1)

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        lookupsMayEnd.countDown()
        closeables.asReversed().forEach { runCatching { it.close() } }
    }

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(30.seconds) { body() } }

    private val accept = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }

    @Test
    fun `a caller cancelled at hand-over gets no session, and the session is closed`() = test {
        val server = TestSshServer().closeLater()
        val handedOver = AtomicReference<SshSession>()
        lateinit var caller: Job
        val connector = SshjConnector(
            NoSessionFeatures,
            beforeHandOver = { session ->
                handedOver.set(session)
                // The connect has finished; the caller goes away before it gets the result.
                caller.cancel()
            },
        )
        withContext(Dispatchers.Default) {
            // Lazy, so the callback cannot run before caller is set.
            val connecting = async(start = CoroutineStart.LAZY) {
                connector.connect(
                    server.target,
                    password(),
                    accept,
                    SshConnectOptions(keepAliveInterval = 100.milliseconds),
                ).closeLater()
            }
            caller = connecting
            connecting.start()
            assertFailsWith<CancellationException> { connecting.await() }
        }
        val session = handedOver.get() as SshjSession
        assertFalse(session.connected.value, "The undelivered session must be closed")
        eventually("keep-alive stopped") { !session.keepAliveRunning }
        eventually("server session closed") { server.openSessions() == 0 }
        eventually("sshj threads ended") { sshjThreadsAlive().isEmpty() }
    }

    @Test
    fun `a name lookup that blocks still ends in Timeout within the limit`() = test {
        val server = TestSshServer().closeLater()
        val lookupStarted = CountDownLatch(1)
        val connector = SshjConnector(
            NoSessionFeatures,
            resolver = HostResolver {
                lookupStarted.countDown()
                lookupsMayEnd.await()
                InetAddress.getByName("127.0.0.1")
            },
        )
        val target = SshTarget("blocked.invalid", server.port, TestSecrets.USER)
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SshException.Timeout> {
            connector.connect(target, password(), accept, SshConnectOptions(connectTimeout = 300.milliseconds))
        }
        assertTrue(started.elapsedNow() < 2.seconds, "Took ${started.elapsedNow()}")
        assertEquals(0L, lookupStarted.count, "The lookup must have started")

        // The abandoned lookup ends later; its answer must not open a connection.
        lookupsMayEnd.countDown()
        delay(300.milliseconds)
        assertEquals(0, server.openSessions())
        assertEquals(0, server.credentialsReceived())
        assertTrue(sshjThreadsAlive().isEmpty())
    }

    @Test
    fun `the resolver's address is the one connected to`() = test {
        val server = TestSshServer().closeLater()
        val connector = SshjConnector(NoSessionFeatures, resolver = HostResolver { InetAddress.getByName("127.0.0.1") })
        val target = SshTarget("server.invalid", server.port, TestSecrets.USER)
        assertTrue(connector.connect(target, password(), accept).closeLater().connected.value)
    }

    @Test
    fun `a name that does not resolve fails with ConnectionFailed`() = test {
        val connector = SshjConnector(NoSessionFeatures, resolver = HostResolver { throw UnknownHostException(it) })
        val target = SshTarget("missing.invalid", 22, TestSecrets.USER)
        val failure = assertFailsWith<SshException.ConnectionFailed> { connector.connect(target, password(), accept) }
        assertFalse("missing.invalid" in failure.stackTraceToString())
    }

    private companion object {
        fun sshjThreadsAlive(): List<Thread> =
            Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("sshj-") }

        suspend fun eventually(what: String, within: Duration = 5.seconds, condition: () -> Boolean) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > within) throw AssertionError("Not within $within: $what")
                delay(20.milliseconds)
            }
        }
    }
}
