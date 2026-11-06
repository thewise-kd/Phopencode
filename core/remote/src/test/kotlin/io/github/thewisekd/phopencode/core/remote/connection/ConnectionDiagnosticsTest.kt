package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnector
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.HostKeyUpdate
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * P9.25: with [Diagnostics] on, the manager records why the connection last ended, as a trace
 * captured at that moment, and logs it; with diagnostics off it records and logs nothing.
 */
class ConnectionDiagnosticsTest {
    private class RecordingLog : ConnectionEndLog {
        val traces = mutableListOf<Throwable>()

        override fun ended(trace: Throwable) {
            traces += trace
        }
    }

    /** Runs [fail] instead of the real connect for the first connect only. */
    private class FailingConnector(private val real: SshConnector, private val fail: suspend () -> Nothing) : SshConnector {
        private var failed = false

        override suspend fun connect(
            target: SshTarget,
            credential: SshCredential,
            hostKeyVerifier: HostKeyVerifier,
            options: SshConnectOptions,
        ): SshSession {
            if (!failed) {
                failed = true
                fail()
            }
            return real.connect(target, credential, hostKeyVerifier, options)
        }
    }

    private fun chain(trace: Throwable?): List<Throwable> =
        generateSequence(trace) { current -> current.cause?.takeIf { it !== current } }.toList()

    private fun TestScope.connected(fx: Fixture) {
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Connected>(fx.manager.state.value)
    }

    private suspend fun TestScope.prepared(fx: Fixture): Fixture {
        fx.seedSecrets()
        fx.trustServerKey()
        return fx
    }

    /** A named caller, so that the trace's stack can be checked for it. */
    private suspend fun disconnectFromTheTest(manager: ConnectionManager) {
        manager.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun cancelRunFromInside(managerJob: Job) {
        val own = currentCoroutineContext().job
        val run = generateSequence(own) { it.parent }.first { it.parent === managerJob }
        run.cancel(CancellationException("stray"))
    }

    @Test
    fun `diagnostics are off by default`() = runTest {
        val fx = Fixture(this)

        assertEquals(Diagnostics.Off, fx.manager.diagnostics)
        assertEquals(false, Diagnostics.Off.enabled)
    }

    @Test
    fun `disconnect records a trace whose stack shows the caller`() = runTest {
        val log = RecordingLog()
        val fx = prepared(Fixture(this, diagnostics = Diagnostics(enabled = true), endLog = log))
        connected(fx)

        disconnectFromTheTest(fx.manager)

        val trace = assertNotNull(fx.manager.lastEnd)
        assertEquals("disconnect() called", trace.message)
        assertTrue(
            trace.stackTrace.any { it.methodName.contains("disconnectFromTheTest") },
            "the caller is not in the stack: ${trace.stackTrace.joinToString("\n")}",
        )
        assertSame(trace, log.traces.last())
    }

    @Test
    fun `a stray cancellation inside a step records the cancellation`() = runTest {
        val stray = CancellationException("stray")
        val log = RecordingLog()
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { FailingConnector(it) { throw stray } },
                diagnostics = Diagnostics(enabled = true),
                endLog = log,
            ),
        )

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Error>(fx.manager.state.value)
        assertTrue(chain(fx.manager.lastEnd).any { it === stray }, "chain: ${chain(fx.manager.lastEnd)}")
        assertSame(fx.manager.lastEnd, log.traces.last())
    }

    @Test
    fun `a stray cancellation of the run's job records its cause`() = runTest {
        val managerJob = backgroundScope.coroutineContext.job
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { real ->
                    FailingConnector(real) {
                        cancelRunFromInside(managerJob)
                        awaitCancellation()
                    }
                },
                diagnostics = Diagnostics(enabled = true),
            ),
        )

        fx.manager.connect(profile())
        runCurrent()

        assertIs<ConnectionState.Error>(fx.manager.state.value)
        val links = chain(fx.manager.lastEnd)
        assertTrue(links.any { it is CancellationException && it.message == "stray" }, "chain: $links")
    }

    @Test
    fun `the end of the manager's scope records the scope's cancellation cause`() = runTest {
        val managerJob = Job()
        val fx = prepared(
            Fixture(
                this,
                managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler)),
                diagnostics = Diagnostics(enabled = true),
            ),
        )
        connected(fx)

        managerJob.cancel(CancellationException("scope gone"))
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        val trace = assertNotNull(fx.manager.lastEnd)
        assertTrue(trace.message.orEmpty().contains("scope"), "message: ${trace.message}")
        assertTrue(chain(trace).any { it.message == "scope gone" }, "chain: ${chain(trace)}")
    }

    @Test
    fun `close records that close was called`() = runTest {
        val fx = prepared(Fixture(this, diagnostics = Diagnostics(enabled = true)))
        connected(fx)

        fx.manager.close()

        assertEquals("close() called", assertNotNull(fx.manager.lastEnd).message)
    }

    @Test
    fun `a dropped SSH session is recorded`() = runTest {
        val fx = prepared(Fixture(this, diagnostics = Diagnostics(enabled = true)))
        connected(fx)

        fx.connector.sessions.single().loseConnection()
        runCurrent()

        assertIs<ConnectionState.Reconnecting>(fx.manager.state.value)
        val trace = assertNotNull(fx.manager.lastEnd)
        assertTrue(trace.message.orEmpty().contains("Disconnected"), "message: ${trace.message}")
    }

    @Test
    fun `a failed connect records the underlying exception`() = runTest {
        val failure = SshException.ConnectionFailed(java.io.IOException("unreachable for the test"))
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { FailingConnector(it) { throw failure } },
                diagnostics = Diagnostics(enabled = true),
            ),
        )

        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.Unreachable), fx.manager.state.value)
        assertTrue(chain(fx.manager.lastEnd).any { it === failure }, "chain: ${chain(fx.manager.lastEnd)}")
    }

    @Test
    fun `a connect that replaces a standing connection is recorded`() = runTest {
        val fx = prepared(Fixture(this, diagnostics = Diagnostics(enabled = true)))
        connected(fx)

        fx.manager.connect(profile())

        val trace = assertNotNull(fx.manager.lastEnd)
        assertTrue(trace.message.orEmpty().contains("connect()"), "message: ${trace.message}")
    }

    @Test
    fun `a connect delayed after its state change cannot overwrite a newer close`() = runTest {
        val managerJob = Job()
        val log = RecordingLog()
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { FailingConnector(it) { throw SshException.ConnectionFailed() } },
                managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler)),
                diagnostics = Diagnostics(enabled = true),
                endLog = log,
            ),
        )
        // The first connect fails and its run completes, so the next connect replaces it.
        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Error>(fx.manager.state.value)

        // The scope ends between the connect's state change and anything the connect does after
        // it: the newer close must stay the last end.
        fx.manager.afterConnectSwitched = {
            fx.manager.afterConnectSwitched = null
            managerJob.cancel(CancellationException("scope gone"))
        }
        fx.manager.connect(profile())
        runCurrent()

        assertEquals(ConnectionState.Closed, fx.manager.state.value)
        val trace = assertNotNull(fx.manager.lastEnd)
        assertTrue(trace.message.orEmpty().contains("scope"), "message: ${trace.message}")
        assertTrue(log.traces.any { it.message.orEmpty().contains("connect()") }, "the replace is still logged")
    }

    @Test
    fun `a collector woken by a new state reads the end that explains it`() = runTest {
        val failure = SshException.ConnectionFailed(java.io.IOException("unreachable for the test"))
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { FailingConnector(it) { throw failure } },
                diagnostics = Diagnostics(enabled = true),
            ),
        )
        // Reads lastEnd on the writer's thread, the moment each state is written.
        val seen = mutableListOf<Pair<ConnectionState, Throwable?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.collect { seen += it to fx.manager.lastEnd }
        }

        fx.manager.connect(profile())
        runCurrent()
        connected(fx)
        disconnectFromTheTest(fx.manager)
        fx.manager.close()

        val error = seen.single { it.first is ConnectionState.Error }
        assertTrue(chain(error.second).any { it === failure }, "Error read: ${error.second}")
        assertEquals("disconnect() called", seen.single { it.first == ConnectionState.Idle && it.second != null }.second?.message)
        assertEquals("close() called", seen.single { it.first == ConnectionState.Closed }.second?.message)
    }

    @Test
    fun `an approved key that cannot be stored records its end before the Error shows`() = runTest {
        val storeFailure = java.io.IOException("known hosts not writable")
        val fx = Fixture(
            this,
            wrapKnownHosts = { real ->
                object : KnownHostsStore by real {
                    override suspend fun approve(endpoint: HostEndpoint, fingerprint: HostKeyFingerprint): HostKeyUpdate =
                        throw storeFailure
                }
            },
            diagnostics = Diagnostics(enabled = true),
        )
        fx.seedSecrets()
        val seen = mutableListOf<Pair<ConnectionState, Throwable?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fx.manager.state.collect { seen += it to fx.manager.lastEnd }
        }
        fx.manager.connect(profile())
        runCurrent()
        val asked = assertIs<ConnectionState.HostApproval>(fx.manager.state.value)

        assertEquals(false, fx.manager.approveHostKey(asked))
        runCurrent()

        assertEquals(ConnectionState.Error(ConnectionError.StorageUnavailable), fx.manager.state.value)
        val errors = seen.filter { it.first is ConnectionState.Error }
        assertEquals(1, errors.size, "states: $seen")
        assertTrue(chain(errors.single().second).any { it === storeFailure }, "Error read: ${errors.single().second}")
        assertTrue(chain(fx.manager.lastEnd).any { it === storeFailure }, "a later record replaced it: ${fx.manager.lastEnd}")
    }

    @Test
    fun `the java logging end log masks credentials and passes no throwable`() {
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger(ConnectionEndLog.TAG)
        val value = listOf("Zq", "8w", "Lm", "4x").joinToString("")
        val cause = java.io.IOException("login refused, pass" + "word=" + value)
        logger.addHandler(handler)
        try {
            ConnectionEndLog.JavaLogging.ended(ConnectionEndTrace("dropped: Disconnected", cause))
        } finally {
            logger.removeHandler(handler)
        }

        val record = records.single()
        assertNull(record.thrown, "a raw throwable reaches the log")
        val text = record.message
        assertFalse(value in text, text)
        assertTrue(DiagnosticText.MASK in text, text)
        assertTrue("dropped: Disconnected" in text && "Caused by: java.io.IOException" in text, text)
        assertTrue("    at " in text, text)
    }

    @Test
    fun `with diagnostics off nothing is recorded or logged`() = runTest {
        val stray = CancellationException("stray")
        val log = RecordingLog()
        val managerJob = Job()
        val fx = prepared(
            Fixture(
                this,
                wrapConnector = { FailingConnector(it) { throw stray } },
                managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler)),
                endLog = log,
            ),
        )

        fx.manager.connect(profile())
        runCurrent()
        assertIs<ConnectionState.Error>(fx.manager.state.value)
        connected(fx)
        fx.connector.sessions.last().loseConnection()
        runCurrent()
        connected(fx)
        disconnectFromTheTest(fx.manager)
        connected(fx)
        managerJob.cancel(CancellationException("scope gone"))
        runCurrent()
        fx.manager.close()

        assertNull(fx.manager.lastEnd)
        assertEquals(emptyList(), log.traces)
    }
}
