package io.github.thewisekd.phopencode.core.ssh.exec

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

// A blocked sshj thread ignores coroutine cancellation; this ends such a test instead of the build.
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshjRemoteExecTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @TempDir
    lateinit var dir: Path

    private fun <T : AutoCloseable> T.closeLater(): T = also { closeables += it }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        // The server kills what it started; nothing may outlive a test.
        val left = closeables.filterIsInstance<ExecTestServer>().flatMap { it.survivors() }
        assertEquals(emptyList(), left, "Processes left running")
    }

    // The first test of a JVM has taken 37 s on a loaded Pi, most of it warm-up.
    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(90.seconds) { body() } }

    private suspend fun session(server: ExecTestServer = ExecTestServer().closeLater()): SshSession =
        server.connect().closeLater()

    private val pidFile: Path get() = dir.resolve("pid")

    /** Starts a child that would run for ten minutes and writes its pid to [pidFile] first. */
    private val longChild: String get() = "$startChild wait"

    private val startChild: String
        get() = "sleep 600 & echo \$! > ${ShellQuote.quote(pidFile.toString())};"

    private suspend fun awaitPid(): Long {
        eventually("pid written") { LocalProcesses.readPid(pidFile) != null }
        return LocalProcesses.readPid(pidFile)!!
    }

    // run: output and exit

    @Test
    fun `run returns stdout and stderr separately, and the exit code`() = test {
        val result = session().exec.run(ExecRequest("printf out; printf err >&2; exit 3"))
        assertEquals("out", result.stdout.decodeToString())
        assertEquals("err", result.stderr.decodeToString())
        assertEquals(3, result.exitCode)
        assertNull(result.exitSignal)
        assertFalse(result.succeeded)
    }

    @Test
    fun `run reports success for exit 0`() = test {
        val result = session().exec.run(ExecRequest("true"))
        assertEquals(0, result.exitCode)
        assertTrue(result.succeeded)
        assertEquals(0, result.stdout.size)
        assertEquals(0, result.stderr.size)
    }

    @Test
    fun `output is byte exact, also when it is not UTF-8`() = test {
        val result = session().exec.run(ExecRequest("printf '\\377\\000\\001\\n'; printf '\\376' >&2"))
        assertContentEquals(byteArrayOf(-1, 0, 1, '\n'.code.toByte()), result.stdout)
        assertContentEquals(byteArrayOf(-2), result.stderr)
    }

    @Test
    fun `a quoted argument arrives unchanged`() = test {
        val value = "it's \$HOME `id` \$(id) ; \" \\ ğüşİ"
        val result = session().exec.run(ExecRequest("printf %s " + ShellQuote.quote(value)))
        assertEquals(value, result.stdout.decodeToString())
    }

    @Test
    fun `stderr written before the PID line, beyond the channel window, cannot block the start`() = test {
        val size = 4 * 1024 * 1024
        val server = ExecTestServer(startupStderrBytes = size).closeLater()
        val exec = session(server).exec
        val result = exec.run(ExecRequest("echo ok"))
        assertEquals("ok\n", result.stdout.decodeToString())
        assertEquals(size, result.stderr.size)
        assertEquals(0, result.exitCode)

        val events = exec.stream(ExecRequest("echo ok")).toList()
        assertEquals(size, events.filterIsInstance<ExecEvent.Stderr>().sumOf { it.bytes.size })
        assertEquals("ok\n", events.filterIsInstance<ExecEvent.Stdout>().joinBytes().decodeToString())

        // The limit applies to that output too.
        assertFailsWith<SshException.OutputLimitExceeded> { exec.run(ExecRequest("echo ok"), maxOutputBytes = 1024) }
    }

    @Test
    fun `large output arrives complete`() = test {
        val size = 3 * 1024 * 1024
        val result = session().exec.run(ExecRequest("head -c $size /dev/zero; head -c $size /dev/zero >&2"))
        assertEquals(size, result.stdout.size)
        assertEquals(size, result.stderr.size)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `a death by signal is reported as the signal, without an exit code`() = test {
        val result = session().exec.run(ExecRequest("kill -s KILL \$\$"))
        assertNull(result.exitCode)
        assertEquals("KILL", result.exitSignal)
    }

    @Test
    fun `a server that reports no exit gives neither code nor signal`() = test {
        val server = ExecTestServer(exitReport = ExitReport.Nothing).closeLater()
        val result = session(server).exec.run(ExecRequest("printf done"))
        assertEquals("done", result.stdout.decodeToString())
        assertNull(result.exitCode)
        assertNull(result.exitSignal)
    }

    @Test
    fun `output from a server without a POSIX shell passes through unchanged`() = test {
        val bytes = "phopencode-exec-pid=12x\nrest".encodeToByteArray()
        val server = ExecTestServer(fixedStdout = bytes).closeLater()
        val result = session(server).exec.run(ExecRequest("anything"))
        assertContentEquals(bytes, result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `a command's own output that looks like the internal header passes through`() = test {
        val result = session().exec.run(ExecRequest("printf 'phopencode-exec-pid=5\\n'"))
        assertEquals("phopencode-exec-pid=5\n", result.stdout.decodeToString())
    }

    @Test
    fun `run leaves no channel open`() = test {
        val server = ExecTestServer().closeLater()
        val exec = session(server).exec
        repeat(3) { exec.run(ExecRequest("true")) }
        eventually("channels closed") { server.openChannels() == 0 }
    }

    // stdin

    @Test
    fun `stdin is written and then closed`() = test {
        val input = Random(7).nextBytes(1024 * 1024 + 13)
        val result = session().exec.run(ExecRequest("cat", stdin = input))
        assertContentEquals(input, result.stdout)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `stdin is not copied, so the caller can wipe it afterwards`() = test {
        val input = "fake-password\n".encodeToByteArray()
        val result = session().exec.run(ExecRequest("cat", stdin = input))
        assertEquals("fake-password\n", result.stdout.decodeToString())
        input.fill(0)
        assertTrue(input.all { it == 0.toByte() })
    }

    @Test
    fun `stdin reaches a command that closed its stdout and stderr first`() = test {
        // Larger than the server's window, so the write is still going when the outputs close.
        val input = Random(11).nextBytes(8 * 1024 * 1024 + 7)
        val file = dir.resolve("received")
        val command = "exec >&- 2>&-; sleep 0.5; cat > ${ShellQuote.quote(file.toString())}"
        val result = session().exec.run(ExecRequest(command, stdin = input))
        assertEquals(0, result.exitCode)
        assertContentEquals(input, Files.readAllBytes(file))
    }

    @Test
    fun `null stdin is closed at once, so a reading command ends`() = test {
        val result = session().exec.run(ExecRequest("cat; echo end"))
        assertEquals("end\n", result.stdout.decodeToString())
    }

    @Test
    fun `a command that ignores its stdin still finishes`() = test {
        val input = ByteArray(4 * 1024 * 1024)
        val exec = session().exec
        val started = TimeSource.Monotonic.markNow()
        val result = exec.run(ExecRequest("exec 0<&-; echo ok", stdin = input))
        assertEquals("ok\n", result.stdout.decodeToString())
        // Not held up by the blocked write until sshj's own wait (15 s) gives up.
        assertTrue(started.elapsedNow() < 8.seconds, "Took ${started.elapsedNow()}")
    }

    // Output limit

    @Test
    fun `stdout over the limit fails and ends the process`() = test {
        val failure = assertFailsWith<SshException.OutputLimitExceeded> {
            session().exec.run(ExecRequest("$startChild head -c 100000 /dev/zero; wait"), maxOutputBytes = 1000)
        }
        assertEquals(1000, failure.limitBytes)
        val pid = awaitPid()
        eventually("process ended") { !LocalProcesses.alive(pid) }
    }

    @Test
    fun `stderr over the limit fails`() = test {
        val failure = assertFailsWith<SshException.OutputLimitExceeded> {
            session().exec.run(ExecRequest("head -c 5000 /dev/zero >&2"), maxOutputBytes = 4999)
        }
        assertEquals(4999, failure.limitBytes)
    }

    @Test
    fun `output exactly at the limit is accepted`() = test {
        val result = session().exec.run(ExecRequest("head -c 5000 /dev/zero"), maxOutputBytes = 5000)
        assertEquals(5000, result.stdout.size)
    }

    @Test
    fun `a negative limit is rejected`() = test {
        assertFailsWith<IllegalArgumentException> { session().exec.run(ExecRequest("true"), maxOutputBytes = -1) }
    }

    // Timeout and cancellation

    @Test
    fun `a timeout gives Timeout and ends the remote process`() = test {
        val server = ExecTestServer().closeLater()
        val exec = session(server).exec
        val started = TimeSource.Monotonic.markNow()
        assertFailsWith<SshException.Timeout> { exec.run(ExecRequest(longChild, timeout = 700.milliseconds)) }
        assertTrue(started.elapsedNow() < 10.seconds, "Took ${started.elapsedNow()}")
        val pid = awaitPid()
        eventually("process ended") { !LocalProcesses.alive(pid) }
        eventually("channel closed") { server.openChannels() == 0 }
    }

    @Test
    fun `a process that ignores TERM still ends on timeout`() = test {
        val command = "trap '' TERM HUP INT; $longChild"
        assertFailsWith<SshException.Timeout> { session().exec.run(ExecRequest(command, timeout = 700.milliseconds)) }
        val pid = awaitPid()
        eventually("process ended") { !LocalProcesses.alive(pid) }
    }

    @Test
    fun `cancelling run ends the remote process and closes the channel`() = test {
        val server = ExecTestServer().closeLater()
        val exec = session(server).exec
        withContext(Dispatchers.Default) {
            val running = async { exec.run(ExecRequest(longChild, timeout = null)) }
            val pid = awaitPid()
            assertTrue(LocalProcesses.alive(pid))
            running.cancel()
            assertFailsWith<CancellationException> { running.await() }
            eventually("process ended") { !LocalProcesses.alive(pid) }
            eventually("channel closed") { server.openChannels() == 0 }
        }
    }

    @Test
    fun `cancelling run while stdin is blocked returns promptly`() = test {
        val exec = session().exec
        withContext(Dispatchers.Default) {
            // The command never reads, so the write blocks once the window is full.
            val input = ByteArray(16 * 1024 * 1024)
            val running = async { exec.run(ExecRequest("echo \$\$ > ${ShellQuote.quote(pidFile.toString())}; sleep 600", input, null)) }
            val pid = awaitPid()
            delay(300.milliseconds)
            val started = TimeSource.Monotonic.markNow()
            running.cancel()
            assertFailsWith<CancellationException> { running.await() }
            assertTrue(started.elapsedNow() < 10.seconds, "Took ${started.elapsedNow()}")
            eventually("process ended") { !LocalProcesses.alive(pid) }
        }
    }

    // stream

    @Test
    fun `stream emits output in order and ends with Exited`() = test {
        val command = "printf a; sleep 0.3; printf b >&2; sleep 0.3; printf c; exit 4"
        val events = session().exec.stream(ExecRequest(command)).toList()
        val kinds = events.map { it.kind() }.fold(mutableListOf<String>()) { acc, kind ->
            if (acc.lastOrNull() != kind) acc += kind
            acc
        }
        assertEquals(listOf("out", "err", "out", "exit"), kinds)
        assertEquals("ac", events.filterIsInstance<ExecEvent.Stdout>().joinBytes().decodeToString())
        assertEquals("b", events.filterIsInstance<ExecEvent.Stderr>().joinBytes().decodeToString())
        assertEquals(ExecEvent.Exited(4, null), events.last())
    }

    @Test
    fun `stream passes stdin`() = test {
        val events = session().exec.stream(ExecRequest("cat", stdin = "hello".encodeToByteArray())).toList()
        assertEquals("hello", events.filterIsInstance<ExecEvent.Stdout>().joinBytes().decodeToString())
        assertEquals(ExecEvent.Exited(0, null), events.last())
    }

    @Test
    fun `stream reports a signal`() = test {
        val events = session().exec.stream(ExecRequest("kill -s KILL \$\$")).toList()
        assertEquals(ExecEvent.Exited(null, "KILL"), events.last())
    }

    @Test
    fun `stopping collection after the first line ends a process that keeps running`() = test {
        val server = ExecTestServer().closeLater()
        val exec = session(server).exec
        // The child's pid is on file before "ready", so stopping at "ready" cannot outrun it.
        val first = exec.stream(ExecRequest("$startChild echo ready; wait", timeout = null)).first()
        assertEquals("ready\n", (first as ExecEvent.Stdout).bytes.decodeToString())
        val pid = awaitPid()
        eventually("process ended") { !LocalProcesses.alive(pid) }
        eventually("channel closed") { server.openChannels() == 0 }
    }

    @Test
    fun `each collection starts the process once`() = test {
        val server = ExecTestServer().closeLater()
        val flow = session(server).exec.stream(ExecRequest("echo x"))
        assertEquals(server.commandsStarted(), 0)
        flow.toList()
        flow.toList()
        assertEquals(2, server.commandsStarted())
    }

    @Test
    fun `stream timeout fails collection with Timeout and ends the process`() = test {
        val flow = session().exec.stream(ExecRequest(longChild, timeout = 700.milliseconds))
        assertFailsWith<SshException.Timeout> { flow.toList() }
        val pid = awaitPid()
        eventually("process ended") { !LocalProcesses.alive(pid) }
    }

    @Test
    fun `cancelling stream collection ends the process`() = test {
        val exec = session().exec
        withContext(Dispatchers.Default) {
            val collecting = async(start = CoroutineStart.DEFAULT) {
                exec.stream(ExecRequest(longChild, timeout = null)).toList()
            }
            val pid = awaitPid()
            collecting.cancel()
            assertFailsWith<CancellationException> { collecting.await() }
            eventually("process ended") { !LocalProcesses.alive(pid) }
        }
    }

    @Test
    fun `cancelling before the PID is read still ends the command and its child`() = test {
        val server = ExecTestServer().closeLater()
        val execSent = CountDownLatch(1)
        // Holds the start between sending the exec request and reading the PID, until cancelled.
        val session = server.connect(
            exec = { SshjRemoteExec(it, afterExecSent = { execSent.countDown(); Thread.sleep(60_000) }) },
        ).closeLater()
        val shellPidFile = dir.resolve("shell")
        val command = "echo \$\$ > ${ShellQuote.quote(shellPidFile.toString())}; $longChild"
        withContext(Dispatchers.Default) {
            val running = async { session.exec.run(ExecRequest(command, timeout = null)) }
            val child = awaitPid()
            eventually("exec sent") { execSent.count == 0L }
            val shell = assertNotNull(LocalProcesses.readPid(shellPidFile))
            running.cancel()
            assertFailsWith<CancellationException> { running.await() }
            eventually("command ended") { !LocalProcesses.alive(shell) }
            eventually("child ended") { !LocalProcesses.alive(child) }
        }
    }

    @Test
    fun `a server that stops answering cannot hold the cleanup past its cap`() = test {
        val server = ExecTestServer().closeLater()
        val proxy = FreezableProxy(server.target).closeLater()
        val session = server.connect(via = proxy.target).closeLater()
        withContext(Dispatchers.Default) {
            val running = async { session.exec.run(ExecRequest(longChild, timeout = null)) }
            awaitPid()
            proxy.freeze()
            val started = TimeSource.Monotonic.markNow()
            running.cancelAndJoin()
            assertTrue(started.elapsedNow() < 8.seconds, "Cleanup took ${started.elapsedNow()}")
            // Not left to the session's close: the cleanup's threads end on their own, soon.
            eventually("cleanup threads ended", within = 3.seconds) { cleanupThreads().isEmpty() }
        }
    }

    // Session

    @Test
    fun `closing the session ends a running process, and run fails with Disconnected`() = test {
        val session = session()
        // supervisorScope: the expected failure reaches await only, not the test's scope.
        withContext(Dispatchers.Default) {
            supervisorScope {
                val running = async { session.exec.run(ExecRequest(longChild, timeout = null)) }
                val pid = awaitPid()
                session.close()
                assertFailsWith<SshException.Disconnected> { running.await() }
                eventually("process ended") { !LocalProcesses.alive(pid) }
            }
        }
    }

    @Test
    fun `a dropped connection fails run with Disconnected`() = test {
        val server = ExecTestServer().closeLater()
        val exec = session(server).exec
        withContext(Dispatchers.Default) {
            supervisorScope {
                val running = async { exec.run(ExecRequest(longChild, timeout = null)) }
                val pid = awaitPid()
                server.dropAll()
                assertFailsWith<SshException.Disconnected> { running.await() }
                // A lost connection cannot end the process; the test server's teardown must.
                server.close()
                eventually("process ended") { !LocalProcesses.alive(pid) }
                assertEquals(emptyList(), server.survivors())
            }
        }
    }

    @Test
    fun `run and stream on a closed session fail with Disconnected`() = test {
        val session = session()
        session.close()
        assertFailsWith<SshException.Disconnected> { session.exec.run(ExecRequest("true")) }
        assertFailsWith<SshException.Disconnected> { session.exec.stream(ExecRequest("true")).toList() }
    }

    @Test
    fun `failures do not quote the command`() = test {
        val secretish = "fake-password-in-command"
        val failure = assertFailsWith<SshException.Timeout> {
            session().exec.run(ExecRequest("sleep 5 # $secretish", timeout = 300.milliseconds))
        }
        assertFalse(secretish in failure.stackTraceToString())
    }

    private companion object {
        fun cleanupThreads(): List<Thread> =
            Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("phopencode-ssh-exec-") }

        fun ExecEvent.kind(): String = when (this) {
            is ExecEvent.Stdout -> "out"
            is ExecEvent.Stderr -> "err"
            is ExecEvent.Exited -> "exit"
        }

        fun List<ExecEvent>.joinBytes(): ByteArray = ByteArrayOutputStream().also { out ->
            forEach {
                when (it) {
                    is ExecEvent.Stdout -> out.write(it.bytes)
                    is ExecEvent.Stderr -> out.write(it.bytes)
                    is ExecEvent.Exited -> Unit
                }
            }
        }.toByteArray()

        // Generous: on a loaded Raspberry Pi, starting the kill's shell alone has taken seconds.
        suspend fun eventually(what: String, within: Duration = 20.seconds, condition: () -> Boolean) {
            val started = TimeSource.Monotonic.markNow()
            while (!condition()) {
                if (started.elapsedNow() > within) throw AssertionError("Not within $within: $what")
                delay(20.milliseconds)
            }
        }
    }
}
