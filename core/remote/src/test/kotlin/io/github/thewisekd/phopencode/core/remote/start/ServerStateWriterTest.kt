package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.remote.detect.StateFileLocation
import io.github.thewisekd.phopencode.core.remote.state.ServerState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The writer side of the state file; the parser is tested in `state/ServerStateTest`. */
class ServerStateWriterTest {
    @Test
    fun `encodes to the documented four lines`() {
        val state = ServerState(port = 4096, pid = 1234)
        assertEquals(
            "phopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=1234\n",
            state.encode().decodeToString(),
        )
    }

    @Test
    fun `the shared parser reads what it encodes`() {
        listOf(ServerState(port = 65535, pid = 99999), ServerState(port = 1, pid = 2)).forEach { state ->
            assertEquals(state, ServerState.parse(state.encode()))
        }
    }

    @Test
    fun `discovery and startup agree on the state file's place`() {
        assertEquals(
            "${StateFolder.DIRECTORY}/${StateFolder.FILE_NAME}",
            StateFileLocation.DEFAULT.relativePath,
        )
    }
}
