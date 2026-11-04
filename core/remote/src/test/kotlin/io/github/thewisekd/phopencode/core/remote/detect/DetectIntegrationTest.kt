package io.github.thewisekd.phopencode.core.remote.detect

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Detection over a real SSH connection: the default [SshjConnector] against an embedded MINA server
 * whose commands run on this machine with a temporary `HOME`. Every `opencode` here is a fake
 * script in that folder; the real installation is never run.
 */
class DetectIntegrationTest {
    private val server = DetectSshServer()
    private val processes = mutableListOf<Process>()
    private var session: SshSession? = null

    @AfterTest
    fun tearDown() {
        processes.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        runCatching { session?.close() }
        server.close()
    }

    private suspend fun detector(): ServerDetector {
        val opened = SshjConnector().connect(server.target, detectPassword(), server.verifier)
        session = opened
        return ServerDetector.forSession(opened)
    }

    /** A fake `opencode`: prints [version] for `--version`; `serve` listens on a free port of [bindHost]. */
    private fun fakeOpencode(relative: String, version: String = "2.2.5", bindHost: String = "127.0.0.1"): Path {
        val file = server.home.resolve(relative)
        file.parent.createDirectories()
        file.writeText(
            """
            #!/usr/bin/env python3
            import os, socket, sys, time
            args = sys.argv[1:]
            if args[:1] == ["run"]:
                time.sleep(600)
                sys.exit(0)
            if args[:1] == ["--version"]:
                print(${pythonString(version)})
                sys.exit(0)
            if args[:1] == ["serve"]:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.bind((${pythonString(bindHost)}, 0))
                s.listen(16)
                target = args[args.index("--port-file") + 1]
                with open(target + ".tmp", "w") as f:
                    f.write(str(s.getsockname()[1]))
                os.rename(target + ".tmp", target)
                while True:
                    c, _ = s.accept()
                    c.close()
            sys.exit(2)
            """.trimIndent() + "\n",
        )
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    private fun pythonString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private class Served(val pid: Long, val port: Int)

    /** Starts `opencode serve` as the user would, outside SSH, and waits for its port. */
    private fun serve(binary: Path, label: String = "plain"): Served {
        val portFile = server.home.resolve("port-${UUID.randomUUID()}")
        val builder = ProcessBuilder(binary.toString(), "serve", "--port-file", portFile.toString(), "--label", label)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            clear()
            putAll(server.environment())
        }
        val process = builder.start()
        processes += process
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!portFile.exists()) {
            check(process.isAlive) { "Fake server exited" }
            check(System.nanoTime() < deadline) { "Fake server did not start" }
            Thread.sleep(50)
        }
        return Served(process.pid(), portFile.readText().trim().toInt())
    }

    /** Writes the state file as server startup does: folder 0700, file 0600 unless [mode] says otherwise. */
    private fun writeState(text: String, stateHome: Path = server.home.resolve(".local/state"), mode: String = "rw-------") {
        val file = stateHome.resolve(StateFileLocation.DEFAULT.relativePath)
        file.parent.createDirectories()
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
        file.writeText(text)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode))
    }

    @Test
    fun `finds opencode in the home install folder outside PATH and reads its version`(): Unit = runBlocking {
        val binary = fakeOpencode(".opencode/bin/opencode", version = "opencode 2.2.5")
        assertEquals(
            BinaryLookup.Found(binary.toString(), BinarySource.KnownLocation, VersionCheck.Known(OpencodeVersion(2, 2, 5, null))),
            detector().findBinary(),
        )
    }

    @Test
    fun `finds opencode that only the login profile puts on PATH`(): Unit = runBlocking {
        val binary = fakeOpencode("my tools/opencode", version = "v2.3.0-beta.1")
        server.home.resolve(".profile").writeText(
            "echo 'hello from the profile'\nPATH=\"\$HOME/my tools:\$PATH\"\nexport PATH\n",
        )
        assertEquals(
            BinaryLookup.Found(binary.toString(), BinarySource.LoginPath, VersionCheck.Known(OpencodeVersion(2, 3, 0, "beta.1"))),
            detector().findBinary(),
        )
    }

    @Test
    fun `reports a clear result when opencode is missing`(): Unit = runBlocking {
        assumeTrue(
            listOf("/usr/local/bin", "/usr/bin", "/opt/homebrew/bin", "/home/linuxbrew/.linuxbrew/bin")
                .none { Path.of(it, "opencode").exists() },
            "This machine has opencode in a system folder",
        )
        assertEquals(BinaryLookup.Missing, detector().findBinary())
    }

    @Test
    fun `reports a version it cannot read`(): Unit = runBlocking {
        val binary = fakeOpencode(".opencode/bin/opencode", version = "local")
        assertEquals(
            BinaryLookup.Found(binary.toString(), BinarySource.KnownLocation, VersionCheck.Unparseable),
            detector().findBinary(),
        )
    }

    @Test
    fun `finds a running server and its port by scanning`(): Unit = runBlocking {
        val detector = detector()
        assumeTrue(detector.findServer() is ServerLookup.NotRunning, "Another opencode serve runs on this machine")
        val label = UUID.randomUUID().toString()
        val served = serve(fakeOpencode(".opencode/bin/opencode"), label)

        assertEquals(ServerLookup.Running("127.0.0.1", served.port, served.pid, ServerSource.ProcessScan), detector.findServer())

        // Command lines are matched on the server; none of their words come back.
        val scan = checkNotNull(session).exec.run(ExecRequest(DetectCommands.SCAN))
        val output = scan.stdout.decodeToString()
        assertTrue(output.contains("P ${served.pid}\n"))
        assertFalse(output.contains(label), "The scan sent a command line back")
        assertFalse(output.contains("--port-file"), "The scan sent a command line back")
    }

    @Test
    fun `finds the server the state file names`(): Unit = runBlocking {
        val served = serve(fakeOpencode(".opencode/bin/opencode"))
        writeState(ServerState(served.pid, served.port).toText())
        val detection = detector().detect()
        assertEquals(ServerLookup.Running("127.0.0.1", served.port, served.pid, ServerSource.StateFile), detection.server)
        assertIs<BinaryLookup.Found>(detection.binary)
    }

    @Test
    fun `does not trust a stale state file`(): Unit = runBlocking {
        val gone = ProcessBuilder("true").start().apply { waitFor() }.pid()
        writeState(ServerState(gone, 1).toText())
        val result = detector().findServer()
        val notes = when (result) {
            is ServerLookup.Running -> result.notes.also { assertNotEquals(ServerSource.StateFile, result.source) }
            is ServerLookup.NotRunning -> result.notes
            is ServerLookup.Failed -> error("Detection failed: $result")
        }
        assertTrue(ServerNote.StaleStateFile in notes, "$result")
    }

    @Test
    fun `ignores a state file that is a symbolic link`(): Unit = runBlocking {
        val served = serve(fakeOpencode(".opencode/bin/opencode"))
        val real = server.home.resolve("elsewhere.json")
        real.writeText(ServerState(served.pid, served.port).toText())
        val link = server.home.resolve(".local/state").resolve(StateFileLocation.DEFAULT.relativePath)
        link.parent.createDirectories()
        Files.createSymbolicLink(link, real)
        val result = detector().findServer()
        assertIs<ServerLookup.Running>(result)
        assertEquals(ServerSource.ProcessScan, result.source)
        assertTrue(ServerNote.InvalidStateFile in result.notes)
    }

    @Test
    fun `reads the state file from an absolute XDG_STATE_HOME`(): Unit = runBlocking {
        val served = serve(fakeOpencode(".opencode/bin/opencode"))
        val stateHome = server.home.resolve("xdg state")
        server.extraEnvironment["XDG_STATE_HOME"] = stateHome.toString()
        writeState(ServerState(served.pid, served.port).toText(), stateHome = stateHome)
        assertEquals(ServerLookup.Running("127.0.0.1", served.port, served.pid, ServerSource.StateFile), detector().findServer())
    }

    @Test
    fun `ignores a relative XDG_STATE_HOME`(): Unit = runBlocking {
        val served = serve(fakeOpencode(".opencode/bin/opencode"))
        server.extraEnvironment["XDG_STATE_HOME"] = "relative/state"
        writeState(ServerState(served.pid, served.port).toText())
        assertEquals(ServerLookup.Running("127.0.0.1", served.port, served.pid, ServerSource.StateFile), detector().findServer())
    }

    @Test
    fun `ignores a state file others can read`(): Unit = runBlocking {
        val served = serve(fakeOpencode(".opencode/bin/opencode"))
        writeState(ServerState(served.pid, served.port).toText(), mode = "rw-r--r--")
        val result = detector().findServer()
        assertIs<ServerLookup.Running>(result)
        assertEquals(ServerSource.ProcessScan, result.source)
        assertTrue(ServerNote.InvalidStateFile in result.notes)
    }

    @Test
    fun `a process that only mentions serve is not a server`(): Unit = runBlocking {
        val binary = fakeOpencode(".opencode/bin/opencode")
        val builder = ProcessBuilder(binary.toString(), "run", "serve").redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            clear()
            putAll(server.environment())
        }
        val other = builder.start().also { processes += it }
        val served = serve(binary)
        detector()
        val output = checkNotNull(session).exec.run(ExecRequest(DetectCommands.SCAN)).stdout.decodeToString()
        assertTrue(other.isAlive)
        assertTrue(output.contains("P ${served.pid}\n"), output)
        assertFalse(output.contains("P ${other.pid()}\n"), output)
    }

    @Test
    fun `an executable whose name holds opencode serve is not a server`(): Unit = runBlocking {
        val impostor = serve(fakeOpencode("a b/opencode serve helper"))
        val real = serve(fakeOpencode("x/opencode"))
        detector()
        val output = checkNotNull(session).exec.run(ExecRequest(DetectCommands.SCAN)).stdout.decodeToString()
        assertFalse(output.contains("P ${impostor.pid}\n"), output)
        assertTrue(output.contains("P ${real.pid}\n"), output)
    }
}
