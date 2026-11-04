package io.github.thewisekd.phopencode.core.remote.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerStateTest {
    @Test
    fun `encodes to the documented four lines`() {
        val state = ServerState(port = 4096, pid = 1234)
        assertEquals(
            "phopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=1234\n",
            state.encode().decodeToString(),
        )
        assertEquals("http://127.0.0.1:4096", state.url)
    }

    @Test
    fun `parses what it encodes`() {
        val state = ServerState(port = 65535, pid = 99999)
        assertEquals(state, ServerState.parse(state.encode()))
    }

    @Test
    fun `rejects invalid values on construction`() {
        assertFailsWith<IllegalArgumentException> { ServerState(port = 0, pid = 10) }
        assertFailsWith<IllegalArgumentException> { ServerState(port = 70000, pid = 10) }
        assertFailsWith<IllegalArgumentException> { ServerState(port = 4096, pid = 1) }
    }

    @Test
    fun `parse is strict`() {
        val good = "phopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=1234\n"
        val bad = listOf(
            "",
            good.removeSuffix("\n"),
            good + "\n",
            good + "extra=1\n",
            good.replace("state 1", "state 2"),
            good.replace("127.0.0.1", "0.0.0.0"),
            good.replace("http://", "https://"),
            good.replace("url=http://127.0.0.1:4096", "url=http://127.0.0.1:4097"),
            good.replace("port=4096", "port=04096"),
            good.replace("port=4096", "port=+4096"),
            good.replace("pid=1234", "pid=1"),
            good.replace("pid=1234", "pid=12a"),
            good.replace("pid=1234", "pid=99999999999999999999"),
            good.replace("\n", "\r\n"),
            good.replace("port=4096\n", "") + "port=4096\n",
            good.replace("url=", "url = "),
            "password=x\n$good",
        )
        bad.forEachIndexed { index, text -> assertNull(ServerState.parse(text.encodeToByteArray()), "case $index") }
    }

    @Test
    fun `parse refuses oversized input`() {
        assertNull(ServerState.parse(ByteArray(ServerState.MAX_BYTES + 1) { 'a'.code.toByte() }))
    }
}
