package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class ServerDetectionTest {
    private val exec = FakeRemoteExec()
    private val probed = mutableListOf<Pair<String, Int>>()
    private var answering: Set<Int>? = null

    private val probe = PortProbe { host, port ->
        probed += host to port
        answering?.contains(port) ?: true
    }

    private fun detector(withProbe: Boolean = false, location: StateFileLocation = StateFileLocation.DEFAULT) =
        ServerDetector(exec, if (withProbe) probe else null, location)

    private fun state(output: String, location: StateFileLocation = StateFileLocation.DEFAULT) =
        exec.on(DetectCommands.readState(location)) {
            stdout(output)
            exit(0)
        }

    private fun stateFile(pid: Long, port: Int) = state("present\n" + ServerState(pid, port).toText())

    private fun scan(vararg lines: String) = exec.on(DetectCommands.SCAN) {
        stdout(lines.joinToString("") { "$it\n" })
        exit(0)
    }

    private fun ss(pid: Long, address: String) =
        "S LISTEN 0 512 $address 0.0.0.0:* users:((\"opencode\",pid=$pid,fd=20))"

    @Test
    fun `finds the server named by the state file`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:4096"))
        assertEquals(ServerLookup.Running("127.0.0.1", 4096, 4242, ServerSource.StateFile), detector().findServer())
    }

    @Test
    fun `finds a running server and its port by scanning when there is no state file`() = runTest {
        state("absent\n")
        scan("H proc", "P 777", "H ss", ss(777, "127.0.0.1:38211"), ss(1, "0.0.0.0:22"))
        assertEquals(ServerLookup.Running("127.0.0.1", 38211, 777, ServerSource.ProcessScan), detector().findServer())
    }

    @Test
    fun `finds a server through proc tables when ss is missing`() = runTest {
        state("absent\n")
        scan("H proc", "P 777", "H procnet", "T 777 9001", "N4 0100007F:1000 9001")
        assertEquals(ServerLookup.Running("127.0.0.1", 4096, 777, ServerSource.ProcessScan), detector().findServer())
    }

    @Test
    fun `a state file whose process is gone is stale`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc")
        assertEquals(ServerLookup.NotRunning(setOf(ServerNote.StaleStateFile)), detector().findServer())
    }

    @Test
    fun `a state file whose port is not bound by its process is stale, and the scan still runs`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:5000"), ss(9, "127.0.0.1:4096"))
        assertEquals(
            ServerLookup.Running("127.0.0.1", 5000, 4242, ServerSource.ProcessScan, setOf(ServerNote.StaleStateFile)),
            detector().findServer(),
        )
    }

    @Test
    fun `a state file is stale when its process binds the port on another loopback address`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "[::1]:4096"))
        assertEquals(
            ServerLookup.Running("::1", 4096, 4242, ServerSource.ProcessScan, setOf(ServerNote.StaleStateFile)),
            detector().findServer(),
        )
    }

    @Test
    fun `an unreadable or malformed state file is reported and ignored`() = runTest {
        listOf(
            "present\nphopencode-server-state 1\nurl=http://0.0.0.0:4096\nport=4096\npid=4242\n",
            "present\nphopencode-server-state 2\nurl=http://127.0.0.1:4096\nport=4096\npid=4242\n",
            "present\nphopencode-server-state 1\nurl=http://127.0.0.1:4096\nport=4096\npid=4242",
            "present\nnot a state file",
            "invalid\n",
            "something else\n",
            "",
        ).forEach { output ->
            val exec = FakeRemoteExec()
            exec.on(DetectCommands.readState(StateFileLocation.DEFAULT)) {
                stdout(output)
                exit(0)
            }
            exec.on(DetectCommands.SCAN) {
                stdout("H proc\nP 4242\nH ss\n${ss(4242, "127.0.0.1:4096")}\n")
                exit(0)
            }
            assertEquals(
                ServerLookup.Running("127.0.0.1", 4096, 4242, ServerSource.ProcessScan, setOf(ServerNote.InvalidStateFile)),
                ServerDetector(exec).findServer(),
                output,
            )
        }
    }

    @Test
    fun `never reports a port that is not bound to loopback`() = runTest {
        state("absent\n")
        scan("H proc", "P 777", "H ss", ss(777, "0.0.0.0:4096"), ss(777, "[::]:4097"), ss(777, "192.168.1.9:4098"), ss(777, "*:4099"))
        assertEquals(ServerLookup.NotRunning(setOf(ServerNote.NonLoopbackListener)), detector().findServer())
    }

    @Test
    fun `a state file naming a wildcard listener is not trusted`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "0.0.0.0:4096"))
        assertEquals(
            ServerLookup.NotRunning(setOf(ServerNote.StaleStateFile, ServerNote.NonLoopbackListener)),
            detector().findServer(),
        )
    }

    @Test
    fun `ignores loopback listeners of other programs`() = runTest {
        state("absent\n")
        scan("H proc", "H ss", ss(55, "127.0.0.1:5432"))
        assertEquals(ServerLookup.NotRunning(emptySet()), detector().findServer())
    }

    @Test
    fun `picks the lowest process, then the lowest port`() = runTest {
        state("absent\n")
        scan("H proc", "P 900", "P 300", "H ss", ss(900, "127.0.0.1:1000"), ss(300, "127.0.0.1:6000"), ss(300, "[::1]:5000"))
        assertEquals(ServerLookup.Running("::1", 5000, 300, ServerSource.ProcessScan), detector().findServer())
    }

    @Test
    fun `the port must answer when a probe is given`() = runTest {
        answering = emptySet()
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:4096"))
        assertEquals(
            ServerLookup.NotRunning(setOf(ServerNote.StaleStateFile, ServerNote.PortDidNotAnswer)),
            detector(withProbe = true).findServer(),
        )
        assertEquals(listOf("127.0.0.1" to 4096), probed, "The same port is not probed twice")
    }

    @Test
    fun `moves on to a port that answers`() = runTest {
        answering = setOf(6000)
        state("absent\n")
        scan("H proc", "P 300", "H ss", ss(300, "127.0.0.1:5000"), ss(300, "127.0.0.1:6000"))
        assertEquals(
            ServerLookup.Running("127.0.0.1", 6000, 300, ServerSource.ProcessScan, setOf(ServerNote.PortDidNotAnswer)),
            detector(withProbe = true).findServer(),
        )
    }

    @Test
    fun `without listener tools a live state process is never trusted on a probe alone`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242")
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector(withProbe = true).findServer())
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector(withProbe = false).findServer())
        assertEquals(emptyList(), probed, "An answering port proves nothing about who binds it")
    }

    @Test
    fun `without listener tools a state file whose process is not opencode serve is stale`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H ps")
        assertEquals(ServerLookup.NotRunning(setOf(ServerNote.StaleStateFile)), detector(withProbe = true).findServer())
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `without listener tools other server processes make the answer unknown`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H ps", "P 77")
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector(withProbe = true).findServer())
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `without a process tool a state file is not checked at all`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H ss", ss(4242, "127.0.0.1:4096"))
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector(withProbe = true).findServer())
        assertEquals(emptyList(), probed)
    }

    @Test
    fun `a connection lost while probing is thrown, not reported as a closed port`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:4096"))
        val failing = ServerDetector(exec, PortProbe { _, _ -> throw SshException.Disconnected() })
        assertFailsWith<SshException.Disconnected> { failing.findServer() }
    }

    @Test
    fun `a probe that fails otherwise counts as a port that did not answer`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:4096"))
        val failing = ServerDetector(exec, PortProbe { _, _ -> throw java.net.ConnectException("refused") })
        assertEquals(
            ServerLookup.NotRunning(setOf(ServerNote.StaleStateFile, ServerNote.PortDidNotAnswer)),
            failing.findServer(),
        )
    }

    @Test
    fun `a server process whose port cannot be seen is a clear failure`() = runTest {
        state("absent\n")
        scan("H proc", "P 4242")
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector().findServer())
    }

    @Test
    fun `a server without any process tool is a clear failure`() = runTest {
        state("absent\n")
        scan()
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector().findServer())
    }

    @Test
    fun `a process tool that failed is a clear failure, not an empty server list`() = runTest {
        state("absent\n")
        scan("X ps")
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector().findServer())
    }

    @Test
    fun `a failed proc read with a failed ps fallback is a clear failure`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("X proc", "X ps")
        assertEquals(ServerLookup.Failed(DetectFailure.ToolsUnavailable), detector().findServer())
    }

    @Test
    fun `a failed proc read with a working ps fallback still finds the server`() = runTest {
        state("absent\n")
        scan("X proc", "H ps", "P 777", "H ss", ss(777, "127.0.0.1:4096"))
        assertEquals(ServerLookup.Running("127.0.0.1", 4096, 777, ServerSource.ProcessScan), detector().findServer())
    }

    @Test
    fun `a scan that times out is a clear failure`() = runTest {
        state("absent\n")
        exec.on(DetectCommands.SCAN) { awaitCancellation() }
        assertEquals(ServerLookup.Failed(DetectFailure.TimedOut), detector().findServer())
    }

    @Test
    fun `a state file read that times out is reported and the scan decides`() = runTest {
        exec.on(DetectCommands.readState(StateFileLocation.DEFAULT)) { awaitCancellation() }
        scan("H proc")
        assertEquals(ServerLookup.NotRunning(setOf(ServerNote.InvalidStateFile)), detector().findServer())
    }

    @Test
    fun `reads the state file from the location it is given`() = runTest {
        val location = StateFileLocation("srv/state.json")
        state("present\n" + ServerState(5, 7000).toText(), location)
        scan("H proc", "P 5", "H ss", ss(5, "127.0.0.1:7000"))
        assertEquals(ServerLookup.Running("127.0.0.1", 7000, 5, ServerSource.StateFile), detector(location = location).findServer())
        assertTrue(DetectCommands.readState(location).contains("'srv/state.json'"))
    }

    @Test
    fun `runs only its own fixed commands`() = runTest {
        stateFile(pid = 4242, port = 4096)
        scan("H proc", "P 4242", "H ss", ss(4242, "127.0.0.1:4096"))
        detector().findServer()
        assertEquals(
            listOf(DetectCommands.readState(StateFileLocation.DEFAULT), DetectCommands.SCAN),
            exec.executions.map { it.command },
        )
        assertTrue(exec.executions.all { it.stdin == null })
    }

    @Test
    fun `detect reports binary and server together`() = runTest {
        exec.on(DetectCommands.LOCATE) {
            stdout("known /home/u/.opencode/bin/opencode\n")
            exit(0)
        }
        exec.on(DetectCommands.version("/home/u/.opencode/bin/opencode")) {
            stdout("2.2.5\n")
            exit(0)
        }
        state("absent\n")
        scan("H proc")
        val detection = detector().detect()
        assertEquals(
            BinaryLookup.Found(
                "/home/u/.opencode/bin/opencode",
                BinarySource.KnownLocation,
                VersionCheck.Known(OpencodeVersion(2, 2, 5, null)),
            ),
            detection.binary,
        )
        assertEquals(ServerLookup.NotRunning(emptySet()), detection.server)
    }

    @Test
    fun `a lost connection is not a result`() = runTest {
        exec.disconnect()
        assertFailsWith<SshException.Disconnected> { detector().findServer() }
    }
}
