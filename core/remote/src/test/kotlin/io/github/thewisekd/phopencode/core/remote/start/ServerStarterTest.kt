package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.remote.state.ServerState
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import io.github.thewisekd.phopencode.core.testing.ssh.FakeCommand
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** Command construction and output handling, against core/testing's fake [FakeRemoteExec]. */
class ServerStarterTest {
    private val exec = FakeRemoteExec()
    private val starter = ServerStarter(exec)
    private val options = ServerStartOptions(port = 4096, timeout = 20.seconds)
    private val secret = "pw-" + List(12) { ('a'..'z').random() }.joinToString("")

    private fun password() = SecretValue.ofText(secret)

    private fun givenStart(options: ServerStartOptions = this.options, script: FakeCommand) =
        exec.on(StartScript.startCommand(options), script)

    private fun givenStateWritten(exit: Int = 0) = exec.on(StartScript.WRITE_STATE_COMMAND) { exit(exit) }

    private fun givenStop(pid: Long) = exec.on(StartScript.stopCommand(pid)) { exit(0) }

    @Test
    fun `starts, reports the URL from the ready line and writes the state`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n")
            exit(0)
        }
        givenStateWritten()

        val started = starter.start(password(), options)

        assertEquals(StartedServer("http://127.0.0.1:4096", 4096, 4321), started)
        val (start, write) = exec.executions
        assertEquals(2, exec.executions.size)
        assertContentEquals((secret + "\n").encodeToByteArray(), start.stdin)
        assertContentEquals(ServerState(4096, 4321).encode(), write.stdin)
        assertEquals(StartScript.WRITE_STATE_COMMAND, write.command)
    }

    @Test
    fun `the password is never on a command line`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n")
            exit(0)
        }
        givenStateWritten()

        starter.start(password(), options)

        exec.executions.forEach { assertFalse(secret in it.command) }
        assertFalse(secret in StartScript.SCRIPT)
    }

    @Test
    fun `every argument is quoted and the server binds to loopback`() {
        val command = StartScript.startCommand(ServerStartOptions(binary = "/opt/my tools/opencode", port = 5000, timeout = 7.seconds))
        assertEquals(
            ShellQuote.join(listOf("sh", "-c", StartScript.SCRIPT, "phopencode-start", "/opt/my tools/opencode", "5000", "7")),
            command,
        )
        assertTrue("--hostname 127.0.0.1" in StartScript.SCRIPT)
        val noPort = StartScript.startCommand(ServerStartOptions(timeout = 2500.milliseconds))
        assertEquals(ShellQuote.join(listOf("sh", "-c", StartScript.SCRIPT, "phopencode-start", "opencode", "", "3")), noPort)
    }

    @Test
    fun `the exec request has a backstop timeout beyond the script's own`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n")
            exit(0)
        }
        givenStateWritten()

        starter.start(password(), options)

        val timeout = assertNotNull(exec.executions.first().timeout)
        assertTrue(timeout > options.timeout)
    }

    @Test
    fun `options reject what cannot be passed safely`() {
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(binary = "") }
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(binary = "-opencode") }
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(binary = "open\ncode") }
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(port = 0) }
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(port = 65536) }
        assertFailsWith<IllegalArgumentException> { ServerStartOptions(timeout = 0.seconds) }
    }

    @Test
    fun `a password that cannot travel as one line is refused before anything runs`() = runTest {
        for (bad in listOf("", "two\nlines", "nul\u0000byte")) {
            assertFailsWith<IllegalArgumentException> { starter.start(SecretValue.ofText(bad), options) }
        }
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `the caller's password stays usable and the request copy is wiped`() = runTest {
        var seen: ByteArray? = null
        givenStart {
            seen = stdin
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n")
            exit(0)
        }
        givenStateWritten()
        val password = password()

        starter.start(password, options)

        assertEquals(secret, password.revealText())
        assertNotNull(seen)
    }

    @Test
    fun `missing binary`() = runTest {
        givenStart { stdout("phopencode-start=missing\n"); exit(0) }
        assertIs<ServerStartException.BinaryMissing>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(1, exec.executions.size)
    }

    @Test
    fun `port in use`() = runTest {
        givenStart { stdout("phopencode-start-pid=4321\nphopencode-start=port-in-use\n"); exit(0) }
        assertIs<ServerStartException.PortInUse>(runCatching { starter.start(password(), options) }.exceptionOrNull())
    }

    @Test
    fun `server exits`() = runTest {
        givenStart { stdout("phopencode-start-pid=4321\nphopencode-start=exited\n"); exit(0) }
        assertIs<ServerStartException.Exited>(runCatching { starter.start(password(), options) }.exceptionOrNull())
    }

    @Test
    fun `script timeout`() = runTest {
        givenStart { stdout("phopencode-start-pid=4321\nphopencode-start=timeout\n"); exit(0) }
        assertIs<ServerStartException.Timeout>(runCatching { starter.start(password(), options) }.exceptionOrNull())
    }

    @Test
    fun `exec timeout becomes a start timeout`() = runTest {
        exec.failNext(SshException.Timeout())
        assertIs<ServerStartException.Timeout>(runCatching { starter.start(password(), options) }.exceptionOrNull())
    }

    @Test
    fun `state directory refused`() = runTest {
        givenStart { stdout("phopencode-start=state-dir\n"); exit(0) }
        assertIs<ServerStartException.StateNotWritten>(runCatching { starter.start(password(), options) }.exceptionOrNull())
    }

    @Test
    fun `a ready line with a non-loopback URL stops the server and fails`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://0.0.0.0:4096\n")
            exit(0)
        }
        givenStop(4321)

        val failure = runCatching { starter.start(password(), options) }.exceptionOrNull()

        assertIs<ServerStartException.UnexpectedOutput>(failure)
        assertEquals(listOf(StartScript.startCommand(options), StartScript.stopCommand(4321)), exec.executions.map { it.command })
    }

    @Test
    fun `a ready line on another port stops the server and fails`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4097\n")
            exit(0)
        }
        givenStop(4321)
        assertIs<ServerStartException.UnexpectedOutput>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `output without a status or with garbage is unexpected`() = runTest {
        val outputs = listOf(
            "",
            "phopencode-start-pid=4321\n",
            "phopencode-start=something\n",
            "phopencode-start-pid=1\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n",
            "phopencode-start-pid=abc\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n",
            "phopencode-start-ready=server listening on http://127.0.0.1:4096\n",
        )
        for (output in outputs) {
            val local = FakeRemoteExec()
            local.on(StartScript.startCommand(options)) { stdout(output); exit(0) }
            local.on(StartScript.WRITE_STATE_COMMAND) { exit(0) }
            val failure = runCatching { ServerStarter(local).start(password(), options) }.exceptionOrNull()
            assertIs<ServerStartException.UnexpectedOutput>(failure, output)
            assertFalse(local.executions.any { it.command == StartScript.WRITE_STATE_COMMAND }, output)
        }
    }

    @Test
    fun `a failed state write stops the server and fails`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096\n")
            exit(0)
        }
        givenStateWritten(exit = 1)
        givenStop(4321)

        assertIs<ServerStartException.StateNotWritten>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `failure messages are fixed text`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://0.0.0.0:4096 $secret\n")
            exit(0)
        }
        givenStop(4321)
        val failure = assertNotNull(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertFalse(secret in failure.toString())
        assertNull(failure.cause)
    }

    @Test
    fun `oversized output after launch stops the server`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\n")
            stdout("phopencode-start-ready=server listening on " + "x".repeat(1024 * 1024) + "\n")
            exit(0)
        }
        givenStop(4321)

        assertIs<ServerStartException.UnexpectedOutput>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(listOf(StartScript.startCommand(options), StartScript.stopCommand(4321)), exec.executions.map { it.command })
    }

    @Test
    fun `an oversized chunk that carries the PID still stops the server`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\n" + "x".repeat(1024 * 1024) + "\n")
            exit(0)
        }
        givenStop(4321)

        assertIs<ServerStartException.UnexpectedOutput>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(listOf(StartScript.startCommand(options), StartScript.stopCommand(4321)), exec.executions.map { it.command })
    }

    @Test
    fun `a ready line without its newline is not accepted`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\nphopencode-start-ready=server listening on http://127.0.0.1:4096")
            exit(0)
        }
        givenStop(4321)

        assertIs<ServerStartException.UnexpectedOutput>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `an exec timeout after launch stops the server`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\n")
            kotlinx.coroutines.awaitCancellation()
        }
        givenStop(4321)

        assertIs<ServerStartException.Timeout>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `a connection lost after launch still tries to stop the server and reports the loss`() = runTest {
        givenStart {
            stdout("phopencode-start-pid=4321\n")
            throw SshException.Disconnected()
        }
        givenStop(4321)

        assertIs<SshException.Disconnected>(runCatching { starter.start(password(), options) }.exceptionOrNull())
        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `cancelling after launch stops the server`() = runTest {
        val long = ServerStartOptions(port = 4096, timeout = 1000.seconds)
        givenStart(long) {
            stdout("phopencode-start-pid=4321\n")
            kotlinx.coroutines.awaitCancellation()
        }
        givenStop(4321)

        val job = launch { starter.start(password(), long) }
        // Runs everything due now: the script prints the PID line and the starter reads it.
        testScheduler.runCurrent()
        assertTrue(exec.isRunning(StartScript.startCommand(long)))
        job.cancel()
        job.join()

        assertEquals(StartScript.stopCommand(4321), exec.executions.last().command)
    }

    @Test
    fun `the server inherits a log opened before launch, so renaming it cannot race`() {
        val launch = StartScript.SCRIPT.lines().first { "nohup" in it }
        assertTrue(">&3 2>&3" in launch, launch)
        assertFalse("\$log" in launch, launch)
        assertTrue("exec 3>>\"\$log\"" in StartScript.SCRIPT)
        assertTrue(StartScript.SCRIPT.indexOf("exec 3>&-") < StartScript.SCRIPT.indexOf("mv -f -- \"\$log\""))
    }

    @Test
    fun `the script prints only a bounded ready line`() {
        assertTrue("cut -c1-${StartScript.MAX_READY_CHARS}" in StartScript.SCRIPT)
        assertTrue(StartScript.MAX_READY_CHARS < ServerStarter.MAX_OUTPUT_BYTES / 4)
    }

    @Test
    fun `generated passwords are long, distinct and one line`() {
        val first = ServerPassword.generate().use { it.revealText() }
        val second = ServerPassword.generate().use { it.revealText() }
        assertTrue(first.length >= 40)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(first == second)
    }

    @Test
    fun `stop command runs the stop script with a numeric PID`() {
        assertEquals(
            ShellQuote.join(listOf("sh", "-c", StartScript.STOP_SCRIPT, "phopencode-stop", "4321")),
            StartScript.stopCommand(4321),
        )
        assertTrue("KILL" in StartScript.STOP_SCRIPT)
        assertFailsWith<IllegalArgumentException> { StartScript.stopCommand(1) }
    }
}
