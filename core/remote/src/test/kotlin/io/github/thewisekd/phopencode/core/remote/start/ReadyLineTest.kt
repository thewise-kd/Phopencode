package io.github.thewisekd.phopencode.core.remote.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadyLineTest {
    @Test
    fun `takes the port from the V2 and V1 ready lines`() {
        assertEquals(4096, StartScript.readyPort("server listening on http://127.0.0.1:4096", null))
        assertEquals(4096, StartScript.readyPort("opencode server listening on http://127.0.0.1:4096", null))
        assertEquals(4096, StartScript.readyPort("server listening on http://127.0.0.1:4096/", null))
        assertEquals(4096, StartScript.readyPort("server listening on http://127.0.0.1:4096\r", null))
        assertEquals(5000, StartScript.readyPort("server listening on http://127.0.0.1:5000", 5000))
    }

    @Test
    fun `refuses anything but loopback http on the requested port`() {
        val bad = listOf(
            "server listening on http://0.0.0.0:4096",
            "server listening on http://localhost:4096",
            "server listening on http://[::1]:4096",
            "server listening on http://192.0.2.1:4096",
            "server listening on https://127.0.0.1:4096",
            "server listening on http://127.0.0.1",
            "server listening on http://127.0.0.1:0",
            "server listening on http://127.0.0.1:65536",
            "server listening on http://127.0.0.1:04096",
            "server listening on http://127.0.0.1:4096/path",
            "server listening on http://127.0.0.1:4096 and http://0.0.0.0:4096",
            "server listening on http://user@127.0.0.1:4096",
            "server listening on http://127.0.0.1.evil:4096",
            "server listening on",
            "listening",
            "",
        )
        bad.forEach { assertNull(StartScript.readyPort(it, null), it) }
        assertNull(StartScript.readyPort("server listening on http://127.0.0.1:4096", 5000))
    }
}
