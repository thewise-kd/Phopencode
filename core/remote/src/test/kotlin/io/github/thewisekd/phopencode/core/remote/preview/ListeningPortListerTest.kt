package io.github.thewisekd.phopencode.core.remote.preview

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class ListeningPortListerTest {
    private val exec = FakeRemoteExec()

    private fun lister(maxOutputBytes: Long = 64L * 1024) =
        ListeningPortLister(exec, timeout = 5.seconds, maxOutputBytes = maxOutputBytes)

    private fun ss(output: String, exit: Int = 0) = exec.on(PortListCommands.SS) {
        stdout(output)
        exit(exit)
    }

    private fun procNet(output: String, exit: Int = 0) = exec.on(PortListCommands.PROC_NET) {
        stdout(output)
        exit(exit)
    }

    private fun ports(vararg ports: Int, opencode: Int? = null) =
        PortListing.Listed(ports.map { ListeningPort(it, isOpencode = it == opencode) })

    @Test
    fun `the commands are fixed and never use sudo or kill`() {
        assertEquals("ss -ltnH", PortListCommands.SS)
        assertEquals(PortListCommands.procNetScript("/proc/net/tcp", "/proc/net/tcp6"), PortListCommands.PROC_NET)
        for (command in listOf(PortListCommands.SS, PortListCommands.PROC_NET)) {
            assertFalse(Regex("""\b(sudo|kill|pkill|killall|doas|su)\b""").containsMatchIn(command), command)
        }
    }

    @Test
    fun `lists loopback ports from ss sorted without duplicates and marks the opencode port`() = runTest {
        ss(
            """
            LISTEN 0 511 0.0.0.0:8080 0.0.0.0:*
            LISTEN 0 511 [::]:8080 [::]:*
            LISTEN 0 4096 127.0.0.1:4096 0.0.0.0:*
            LISTEN 0 128 10.0.0.5:22 0.0.0.0:*
            LISTEN 0 128 *:5173 *:*
            LISTEN 0 4096 127.0.0.53%lo:53 0.0.0.0:*
            """.trimIndent() + "\n",
        )
        assertEquals(ports(53, 4096, 5173, 8080, opencode = 4096), lister().list(opencodePort = 4096))
        assertEquals(listOf(PortListCommands.SS), exec.executions.map { it.command })
        assertEquals(5.seconds, exec.executions.single().timeout)
        assertEquals(null, exec.executions.single().stdin)
    }

    @Test
    fun `without an opencode port nothing is marked`() = runTest {
        ss("LISTEN 0 4096 127.0.0.1:4096 0.0.0.0:*\n")
        assertEquals(ports(4096), lister().list())
    }

    @Test
    fun `an empty ss listing is an empty list`() = runTest {
        ss("")
        assertEquals(PortListing.Listed(emptyList()), lister().list(opencodePort = 4096))
        assertEquals(listOf(PortListCommands.SS), exec.executions.map { it.command })
    }

    @Test
    fun `reads the proc tables when ss is missing`() = runTest {
        procNet("E 0001\n4 0100007F:1F90\n4 0500000A:0016\n6 00000000000000000000000000000000:1F90\n6 00000000000000000000000001000000:1000\n")
        assertEquals(ports(4096, 8080, opencode = 4096), lister().list(opencodePort = 4096))
        assertEquals(listOf(PortListCommands.SS, PortListCommands.PROC_NET), exec.executions.map { it.command })
        assertTrue(exec.executions.all { it.timeout == 5.seconds && it.stdin == null })
    }

    @Test
    fun `reads the proc tables when ss fails`() = runTest {
        ss("ss: invalid option -- 'H'\n", exit = 255)
        procNet("E 0001\n4 0100007F:1F90\n")
        assertEquals(ports(8080), lister().list())
    }

    @Test
    fun `reads the proc tables when ss dies from a signal`() = runTest {
        exec.on(PortListCommands.SS) { killedBy("SEGV") }
        procNet("E 0001\n4 0100007F:1F90\n")
        assertEquals(ports(8080), lister().list())
    }

    @Test
    fun `is unsupported when neither ss nor the proc tables work`() = runTest {
        procNet("E 0001\n", exit = PortListCommands.NO_TABLES)
        assertEquals(PortListing.Unsupported, lister().list())
    }

    @Test
    fun `reads big-endian proc tables in the server's order`() = runTest {
        procNet("E 0100\n4 7F000001:1F90\n4 0100007F:1F91\n6 00000000000000000000000000000001:1000\n")
        assertEquals(ports(4096, 8080, opencode = 4096), lister().list(opencodePort = 4096))
    }

    @Test
    fun `proc tables of an unknown byte order are unsupported`() = runTest {
        procNet("E \n4 0100007F:1F90\n4 7F000001:1F91\n")
        assertEquals(PortListing.Unsupported, lister().list())
    }

    @Test
    fun `a failing proc read is a typed failure`() = runTest {
        exec.on(PortListCommands.PROC_NET) {
            stderr("awk: /proc/net/tcp: Permission denied on host example.org\n")
            exit(2)
        }
        val result = lister().list()
        assertEquals(PortListing.Failed(PortListFailure.CommandFailed), result)
        assertFalse("example.org" in result.toString() || "/proc" in result.toString())
    }

    @Test
    fun `a timed out ss is a typed failure and nothing else runs`() = runTest {
        exec.on(PortListCommands.SS) { awaitCancellation() }
        assertEquals(PortListing.Failed(PortListFailure.TimedOut), lister().list())
        assertEquals(listOf(PortListCommands.SS), exec.executions.map { it.command })
    }

    @Test
    fun `a timed out proc read is a typed failure`() = runTest {
        exec.on(PortListCommands.PROC_NET) { awaitCancellation() }
        assertEquals(PortListing.Failed(PortListFailure.TimedOut), lister().list())
    }

    @Test
    fun `output beyond the cap is a typed failure`() = runTest {
        ss("LISTEN 0 4096 127.0.0.1:4096 0.0.0.0:*\n".repeat(100))
        assertEquals(PortListing.Failed(PortListFailure.OutputTooLarge), lister(maxOutputBytes = 1024).list())
    }

    @Test
    fun `a lost connection is a typed failure`() = runTest {
        exec.failNext(SshException.Disconnected())
        assertEquals(PortListing.Failed(PortListFailure.ConnectionLost), lister().list())
    }

    @Test
    fun `a connection lost while reading the proc tables is a typed failure`() = runTest {
        exec.on(PortListCommands.PROC_NET) {
            exec.disconnect()
            awaitCancellation()
        }
        assertEquals(PortListing.Failed(PortListFailure.ConnectionLost), lister().list())
    }

    @Test
    fun `an opencode port out of range is refused`() = runTest {
        assertFailsWith<IllegalArgumentException> { lister().list(opencodePort = 0) }
        assertFailsWith<IllegalArgumentException> { lister().list(opencodePort = 65536) }
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { ListeningPortLister(exec, timeout = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { ListeningPortLister(exec, maxOutputBytes = 0) }
    }
}
