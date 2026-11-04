package io.github.thewisekd.phopencode.core.remote.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The one parser of the server state file, shared by discovery (P3.7) and startup (P3.8). */
class ServerStateTest {
    private val valid = "phopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=4242\n"

    @Test
    fun `reads the format server startup writes`() {
        val state = ServerState(port = 4096, pid = 4242)
        assertEquals(state, ServerState.parse(valid))
        assertEquals(state, ServerState.parse(valid.encodeToByteArray()))
        assertEquals("127.0.0.1", state.host)
        assertEquals("http://127.0.0.1:4096", state.url)
    }

    @Test
    fun `reads the extremes of its ranges`() {
        val text = "phopencode-server-state 1\nurl=http://127.0.0.1:65535\nport=65535\npid=999999999999999999\n"
        assertEquals(ServerState(port = 65535, pid = 999_999_999_999_999_999L), ServerState.parse(text))
        assertEquals(ServerState(port = 1, pid = 2), ServerState.parse(valid.replace("4096", "1").replace("4242", "2")))
    }

    @Test
    fun `refuses anything that is not exactly the four lines`() {
        listOf(
            "",
            valid.removeSuffix("\n"),
            valid + "\n",
            valid + "password=x\n",
            valid + "extra=1\n",
            "\n" + valid,
            "password=x\n$valid",
            valid.replace("\n", "\r\n"),
            valid.replace("state 1", "state 2"),
            valid.replace("state 1", "state 01"),
            valid.replace("127.0.0.1", "0.0.0.0"),
            valid.replace("127.0.0.1", "localhost"),
            valid.replace("http://127.0.0.1:4096", "http://[::1]:4096"),
            valid.replace("http://", "https://"),
            valid.replace("url=http://127.0.0.1:4096", "url=http://127.0.0.1:4097"),
            valid.replace("url=", "url = "),
            valid.replace("port=4096", "port=04096"),
            valid.replace("port=4096", "port=+4096"),
            valid.replace("pid=4242", "pid=0"),
            valid.replace("pid=4242", "pid=1"),
            valid.replace("pid=4242", "pid=-1"),
            valid.replace("pid=4242", "pid=04242"),
            valid.replace("pid=4242", "pid= 4242"),
            valid.replace("pid=4242", "pid=12a"),
            valid.replace("pid=4242", "pid=1000000000000000000"),
            valid.replace("pid=4242", "pid=99999999999999999999"),
            valid.replace("4096", "70000"),
            valid.replace("4096", "0"),
            valid.replace("port=4096\n", "") + "port=4096\n",
            valid.lines().let { listOf(it[0], it[2], it[1], it[3]).joinToString("\n") + "\n" },
            valid.replace("4242", "42é42"),
            """{"pid":4242,"host":"127.0.0.1","port":4096}""",
        ).forEachIndexed { index, text ->
            assertNull(ServerState.parse(text), "case $index as text")
            assertNull(ServerState.parse(text.encodeToByteArray()), "case $index as bytes")
        }
    }

    @Test
    fun `refuses non-ASCII and control bytes`() {
        val bytes = valid.encodeToByteArray()
        listOf(0x00, 0x09, 0x0d, 0x7f, 0x80, 0xff).forEach { bad ->
            val changed = bytes.copyOf().also { it[it.size - 2] = bad.toByte() }
            assertNull(ServerState.parse(changed), "byte $bad")
        }
    }

    @Test
    fun `refuses an oversized file without parsing it`() {
        assertEquals(128, ServerState.MAX_BYTES)
        assertNull(ServerState.parse(valid + "x".repeat(ServerState.MAX_BYTES)))
        assertNull(ServerState.parse(ByteArray(ServerState.MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test
    fun `cannot be built with invalid values`() {
        assertFailsWith<IllegalArgumentException> { ServerState(port = 0, pid = 10) }
        assertFailsWith<IllegalArgumentException> { ServerState(port = 70000, pid = 10) }
        assertFailsWith<IllegalArgumentException> { ServerState(port = 4096, pid = 1) }
        assertFailsWith<IllegalArgumentException> { ServerState(port = 4096, pid = 0) }
    }
}
