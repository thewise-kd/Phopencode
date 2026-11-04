package io.github.thewisekd.phopencode.core.remote.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerStateTest {
    private val valid = "phopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=4242\n"

    @Test
    fun `reads the format server startup writes`() {
        assertEquals(ServerState(4242, 4096), ServerState.parse(valid))
        assertEquals("127.0.0.1", ServerState(4242, 4096).host)
    }

    @Test
    fun `round-trips through its file format`() {
        assertEquals(valid, ServerState(4242, 4096).toText())
        assertEquals(ServerState(1, 65535), ServerState.parse(ServerState(1, 65535).toText()))
    }

    @Test
    fun `refuses anything that is not exactly the four lines`() {
        listOf(
            "",
            valid.removeSuffix("\n"),
            valid + "\n",
            valid + "password=x\n",
            "\n" + valid,
            valid.replace("\n", "\r\n"),
            valid.replace("state 1", "state 2"),
            valid.replace("state 1", "state 01"),
            valid.replace("127.0.0.1", "0.0.0.0"),
            valid.replace("127.0.0.1", "localhost"),
            valid.replace("http://127.0.0.1:4096", "http://[::1]:4096"),
            valid.replace("http://", "https://"),
            valid.replace("url=http://127.0.0.1:4096", "url=http://127.0.0.1:4097"),
            valid.replace("port=4096", "port=04096"),
            valid.replace("pid=4242", "pid=0"),
            valid.replace("pid=4242", "pid=-1"),
            valid.replace("pid=4242", "pid=04242"),
            valid.replace("pid=4242", "pid= 4242"),
            valid.replace("4096", "70000"),
            valid.replace("4096", "0"),
            valid.lines().let { listOf(it[0], it[2], it[1], it[3]).joinToString("\n") + "\n" },
            """{"pid":4242,"host":"127.0.0.1","port":4096}""",
        ).forEach { assertNull(ServerState.parse(it), it) }
    }

    @Test
    fun `refuses an oversized file without parsing it`() {
        assertNull(ServerState.parse(valid + "x".repeat(ServerState.MAX_BYTES)))
    }

    @Test
    fun `cannot be built with invalid values`() {
        assertFailsWith<IllegalArgumentException> { ServerState(0, 4096) }
        assertFailsWith<IllegalArgumentException> { ServerState(7, 70000) }
    }

    @Test
    fun `location in the state folder must be a plain relative path`() {
        assertEquals("phopencode/server.state", StateFileLocation.DEFAULT.relativePath)
        listOf("", "/etc/passwd", "../x", "a/../../x", "a//b", "a/./b", "a/", "a\nb").forEach {
            assertFailsWith<IllegalArgumentException>(it) { StateFileLocation(it) }
        }
    }
}
