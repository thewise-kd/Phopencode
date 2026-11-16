package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.remote.connection.Diagnostics
import io.github.thewisekd.phopencode.core.remote.detect.ServerLookup
import io.github.thewisekd.phopencode.core.remote.detect.ServerSource
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** P9.38: the connection screen's failure detail, only while diagnostics are on. */
class ProgressPresenterDebugDetailTest {
    private val address = listOf(203, 0, 113, 77).joinToString(".")

    private suspend fun TestScope.ready(diagnostics: Diagnostics): ProgressFixture {
        val fx = ProgressFixture(this, diagnostics)
        fx.seed()
        fx.trust()
        return fx
    }

    private fun refused(message: String = "Connection refused") = SshException.ConnectionFailed(
        ConnectException("Failed to connect to $USER@$HOST/$address:22: $message"),
    )

    private fun assertNoIdentity(text: String) {
        for (hidden in listOf(HOST, USER, address, SSH_PASSWORD, OPENCODE_PASSWORD)) {
            assertFalse(hidden.lowercase() in text.lowercase(), "\"$hidden\" survived in:\n$text")
        }
    }

    @Test
    fun `with diagnostics on a failed connect shows its chain, root cause first, redacted`() = runTest {
        val fx = ready(Diagnostics(enabled = true))
        fx.connector.failNext(refused())
        val presenter = fx.presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(ProgressError.Unreachable, state.error)
        val detail = assertNotNull(state.debugDetail)
        assertTrue(detail.startsWith("Root cause: java.net.ConnectException: "), detail)
        assertTrue("Connection refused" in detail, detail)
        assertTrue("SshException\$ConnectionFailed: SSH connection failed" in detail, detail)
        assertNoIdentity(detail)
        assertFalse(detail in state.toString(), "the detail stays out of the state's text")
    }

    @Test
    fun `with diagnostics off nothing is shown or kept`() = runTest {
        val fx = ready(Diagnostics.Off)
        fx.connector.failNext(refused())
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.Unreachable, presenter.state.value.error)
        assertNull(presenter.state.value.debugDetail)
        assertNull(fx.manager.lastEnd)
    }

    @Test
    fun `a failure on a later step has its detail too`() = runTest {
        val fx = ready(Diagnostics(enabled = true))
        fx.tools.server = ServerLookup.Unresponsive("127.0.0.1", 4096, 100, ServerSource.ProcessScan)
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.ServerUnresponsive, presenter.state.value.error)
        val detail = assertNotNull(presenter.state.value.debugDetail)
        assertTrue("ServerUnresponsive" in detail, detail)
        assertNoIdentity(detail)
    }

    @Test
    fun `a retry clears the detail and a new failure replaces it`() = runTest {
        val fx = ready(Diagnostics(enabled = true))
        fx.connector.failNext(refused("first refusal"))
        val presenter = fx.presenter()
        runCurrent()
        assertTrue("first refusal" in assertNotNull(presenter.state.value.debugDetail))

        fx.connector.failNext(SshException.Timeout(SocketTimeoutException("second timeout")))
        presenter.onIntent(ProgressIntent.Retry)
        assertNull(presenter.state.value.debugDetail, "a retry clears the detail at once")

        runCurrent()
        assertEquals(ProgressError.Timeout, presenter.state.value.error)
        val second = assertNotNull(presenter.state.value.debugDetail)
        assertTrue(second.startsWith("Root cause: java.net.SocketTimeoutException: second timeout"), second)
        assertFalse("first refusal" in second, second)

        presenter.onIntent(ProgressIntent.Retry)
        runCurrent()
        assertEquals(ProgressStep.Connected, presenter.state.value.step)
        assertNull(presenter.state.value.debugDetail)
    }

    @Test
    fun `a failure without a throwable shows no detail`() = runTest {
        // Nothing seeded: the server is gone, which no exception explains.
        val fx = ProgressFixture(this, Diagnostics(enabled = true))
        val presenter = fx.presenter()
        runCurrent()

        assertEquals(ProgressError.InvalidProfile, presenter.state.value.error)
        assertNull(presenter.state.value.debugDetail)
    }

    @Test
    fun `unreadable profile storage shows its exception with diagnostics on only`() = runTest {
        for (enabled in listOf(true, false)) {
            val fx = ready(Diagnostics(enabled))
            fx.profiles.unavailable = true
            val presenter = fx.presenter()
            runCurrent()

            assertEquals(ProgressError.StorageUnavailable, presenter.state.value.error)
            assertEquals(enabled, presenter.state.value.debugDetail != null, "diagnostics $enabled")
        }
    }
}
