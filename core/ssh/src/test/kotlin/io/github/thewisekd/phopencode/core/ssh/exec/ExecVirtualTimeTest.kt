package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout

/**
 * Callers test with `runTest`, whose virtual clock skips every delay on its own dispatcher. The
 * request timeout must still be real time there: a command that needs real time to finish must not
 * time out at once, and a short timeout must take its real time.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ExecVirtualTimeTest {
    private val server = ExecTestServer()

    @AfterTest
    fun tearDown() {
        server.close()
        assertEquals(emptyList(), server.survivors(), "Processes left running")
    }

    @Test
    fun `run and stream finish under a test dispatcher instead of timing out at once`() = runTest(timeout = 90.seconds) {
        server.connect().use { session ->
            val result = session.exec.run(ExecRequest("printf out; printf err >&2; exit 3"))
            assertEquals("out", result.stdout.decodeToString())
            assertEquals("err", result.stderr.decodeToString())
            assertEquals(3, result.exitCode)

            val events = session.exec.stream(ExecRequest("printf ok")).toList()
            assertEquals<ExecEvent>(ExecEvent.Exited(0, null), events.last())
        }
    }

    @Test
    fun `a short timeout under a test dispatcher takes its real time`() = runTest(timeout = 90.seconds) {
        server.connect().use { session ->
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<SshException.Timeout> {
                session.exec.run(ExecRequest("exec sleep 600", timeout = 500.milliseconds))
            }
            assertTrue(started.elapsedNow() >= 500.milliseconds, "Timed out after ${started.elapsedNow()}")
        }
    }
}
