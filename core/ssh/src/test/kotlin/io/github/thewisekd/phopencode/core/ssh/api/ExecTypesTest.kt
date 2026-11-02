package io.github.thewisekd.phopencode.core.ssh.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ExecTypesTest {
    private val sentinel = listOf("sentinel", "value").joinToString("-")

    @Test
    fun `a request prints neither command nor stdin`() {
        val request = ExecRequest(command = "login $sentinel", stdin = sentinel.encodeToByteArray())
        assertFalse(sentinel in request.toString(), request.toString())
        assertFalse(sentinel.length.toString() in request.toString(), "a length says how long a password is")
    }

    @Test
    fun `stdin is not copied, so that the caller can wipe it`() {
        val stdin = ByteArray(4)
        assertSame(stdin, ExecRequest("cat", stdin = stdin).stdin)
    }

    @Test
    fun `a request has a timeout unless the caller opts out`() {
        assertEquals(ExecRequest.DEFAULT_TIMEOUT, ExecRequest("true").timeout)
        assertEquals(null, ExecRequest("true", timeout = null).timeout)
    }

    @Test
    fun `invalid requests are rejected without repeating the command`() {
        listOf(
            { ExecRequest(" ") },
            { ExecRequest("$sentinel\u0000") },
            { ExecRequest(sentinel, timeout = Duration.ZERO) },
            { ExecRequest(sentinel, timeout = (-1).seconds) },
        ).forEach { create ->
            val failure = assertFailsWith<IllegalArgumentException> { create() }
            assertFalse(sentinel in failure.message.orEmpty())
        }
    }

    @Test
    fun `a result prints the exit but no output`() {
        val text = ExecResult(
            stdout = sentinel.encodeToByteArray(),
            stderr = sentinel.encodeToByteArray(),
            exitCode = 3,
            exitSignal = null,
        ).toString()

        assertFalse(sentinel in text, text)
        assertTrue("exitCode=3" in text, text)
    }

    @Test
    fun `only exit code zero succeeds`() {
        fun result(code: Int?, signal: String? = null) = ExecResult(ByteArray(0), ByteArray(0), code, signal)

        assertTrue(result(0).succeeded)
        assertFalse(result(1).succeeded)
        assertFalse(result(null, "KILL").succeeded)
        assertFalse(result(null).succeeded)
    }

    @Test
    fun `stream events print no output`() {
        val bytes = sentinel.encodeToByteArray()
        listOf(ExecEvent.Stdout(bytes), ExecEvent.Stderr(bytes)).forEach { event ->
            assertFalse(sentinel in event.toString(), event.toString())
        }
    }
}
