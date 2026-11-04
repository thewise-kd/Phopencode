package io.github.thewisekd.phopencode.core.remote.start

import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyVerifier
import io.github.thewisekd.phopencode.core.ssh.api.SshSession
import io.github.thewisekd.phopencode.core.ssh.connect.SshjConnector
import io.github.thewisekd.phopencode.core.storage_api.SecretValue
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The real [SshjConnector] against an embedded MINA server, with a fake `opencode` script in a
 * temporary HOME that prints a realistic ready line and records its arguments and environment.
 * Every process the fake starts is killed after each test.
 */
class ServerStartIntegrationTest {
    private lateinit var server: StartTestServer
    private lateinit var session: SshSession
    private val secret = ServerPassword.generate().use { it.revealText() }
    private val home: Path get() = server.home
    private val record: Path get() = home.resolve("record")
    private val stateDir: Path get() = home.resolve(".local/state/phopencode")

    @BeforeTest
    fun setUp() = runBlocking {
        server = StartTestServer()
        session = SshjConnector().connect(server.target, server.credential(), ACCEPT)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        runCatching { session.close() }
        runCatching { server.close() }
        recordedPids().forEach { StartTestServer.kill(it) }
        Procs.mentioning(home.toString()).forEach { StartTestServer.kill(it) }
        val deadline = TimeSource.Monotonic.markNow()
        while (Procs.mentioning(home.toString()).isNotEmpty() && deadline.elapsedNow() < 5.seconds) Thread.sleep(20)
        val left = Procs.mentioning(home.toString())
        runCatching { home.deleteRecursively() }
        assertEquals(emptyList(), left, "Processes left running")
    }

    private fun givenFake(mode: String, at: Path = server.bin) {
        at.createDirectories()
        val script = at.resolve("opencode")
        script.writeText(FAKE_OPENCODE)
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        home.resolve("mode").writeText(mode)
    }

    private fun recordedPid(): Long? =
        runCatching { record.resolve("pid").readText().trim().toLong() }.getOrNull()

    /** Every PID any fake server recorded, also per port. */
    private fun recordedPids(): List<Long> = runCatching {
        Files.list(record).use { files ->
            files.toList().filter { it.fileName.toString().startsWith("pid") }
                .mapNotNull { it.readText().trim().toLongOrNull() }
        }
    }.getOrDefault(emptyList())

    private fun stateNames(): List<String> =
        Files.list(stateDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun start(options: ServerStartOptions): Result<StartedServer> = runBlocking {
        runCatching { SecretValue.ofText(secret).use { ServerStarter(session.exec).start(it, options) } }
    }

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    @Test
    fun `starts a detached loopback server and records its state`() {
        givenFake("ok")
        val port = freePort()

        val started = start(ServerStartOptions(port = port, timeout = 20.seconds)).getOrThrow()

        assertEquals("http://127.0.0.1:$port", started.url)
        assertEquals(port, started.port)
        assertEquals(recordedPid(), started.pid)
        assertEquals(listOf("serve", "--hostname", "127.0.0.1", "--port", "$port"), record.resolve("argv").readLines())

        val stateFile = stateDir.resolve("server.state")
        assertEquals("rwx------", mode(stateDir))
        assertEquals("rw-------", mode(stateFile))
        assertEquals("rw-------", mode(stateDir.resolve("server-${started.pid}.log")))
        assertEquals(ServerState(port, started.pid), ServerState.parse(stateFile.readBytes()))
        assertEquals(listOf("server-${started.pid}.log", "server.state"), stateNames())
    }

    @Test
    fun `the password reaches the server through stdin only`() {
        givenFake("ok")
        val needle = secret.encodeToByteArray()
        val seenInCmdline = AtomicInteger()
        val scanned = AtomicInteger()
        val running = AtomicBoolean(true)
        val sampler = thread(name = "cmdline-sampler") {
            while (running.get()) {
                for (pid in Procs.all()) {
                    val cmdline = Procs.cmdline(pid) ?: continue
                    if (Procs.contains(cmdline, needle)) seenInCmdline.incrementAndGet()
                }
                scanned.incrementAndGet()
            }
        }
        val started = try {
            start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        } finally {
            running.set(false)
            sampler.join()
        }

        assertTrue(scanned.get() > 0)
        assertEquals(0, seenInCmdline.get(), "The password appeared in a process command line")
        assertFalse(Procs.contains(Procs.cmdline(started.pid)!!, needle))
        val environment = record.resolve("env").readLines()
        assertTrue("OPENCODE_SERVER_PASSWORD=$secret" in environment)
        assertTrue("OPENCODE_PASSWORD=$secret" in environment)
        assertFalse(Procs.contains(stateDir.resolve("server-${started.pid}.log").readBytes(), needle))
        assertFalse(Procs.contains(stateDir.resolve("server.state").readBytes(), needle))
    }

    @Test
    fun `the server outlives the SSH session`() {
        givenFake("ok")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()

        session.close()
        server.close()
        Thread.sleep(500)

        assertTrue(Procs.alive(started.pid), "The server died with the session")
        assertEquals(listOf("/bin/sh", home.resolve("bin/opencode").toString()), Procs.argv(started.pid)!!.take(2))
    }

    @Test
    fun `finds opencode under the home folder when it is not on PATH`() {
        givenFake("ok", at = home.resolve(".opencode/bin"))
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        assertTrue(Procs.alive(started.pid))
    }

    @Test
    fun `missing binary`() {
        val failure = start(ServerStartOptions(timeout = 5.seconds)).exceptionOrNull()
        assertIs<ServerStartException.BinaryMissing>(failure)
        assertFalse(stateDir.resolve("server.state").exists())
    }

    @Test
    fun `port in use`() {
        givenFake("inuse")
        assertIs<ServerStartException.PortInUse>(start(ServerStartOptions(port = freePort(), timeout = 10.seconds)).exceptionOrNull())
        assertFalse(stateDir.resolve("server.state").exists())
    }

    @Test
    fun `a server that exits without the ready line`() {
        givenFake("exit")
        assertIs<ServerStartException.Exited>(start(ServerStartOptions(timeout = 10.seconds)).exceptionOrNull())
    }

    @Test
    fun `a silent server times out and is stopped`() {
        givenFake("silent")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 2.seconds)).exceptionOrNull()
        assertIs<ServerStartException.Timeout>(failure)
        val pid = recordedPid()!!
        awaitGone(pid)
        assertFalse(stateDir.resolve("server.state").exists())
    }

    @Test
    fun `a server listening beyond loopback is refused and stopped`() {
        givenFake("wide")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 10.seconds)).exceptionOrNull()
        assertIs<ServerStartException.UnexpectedOutput>(failure)
        awaitGone(recordedPid()!!)
        assertFalse(stateDir.resolve("server.state").exists())
    }

    @Test
    fun `a default port comes from the ready line`() {
        givenFake("ok")
        val started = start(ServerStartOptions(timeout = 20.seconds)).getOrThrow()
        assertEquals(4096, started.port)
        assertEquals(listOf("serve", "--hostname", "127.0.0.1"), record.resolve("argv").readLines())
    }

    @Test
    fun `a state file left behind is replaced`() {
        stateDir.createDirectories()
        stateDir.resolve("server.state").writeText("stale")
        givenFake("ok")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        assertContentEquals(ServerState(started.port, started.pid).encode(), stateDir.resolve("server.state").readBytes())
        assertEquals("rw-------", mode(stateDir.resolve("server.state")))
        assertEquals("rwx------", mode(stateDir))
    }

    @Test
    fun `concurrent starts each report their own server`() {
        givenFake("ok")
        val slow = freePort()
        val fast = freePort()
        // The first server prints its ready line after the second one has printed its own.
        home.resolve("delay.$slow").writeText("2")
        val other = runBlocking { SshjConnector().connect(server.target, server.credential(), ACCEPT) }
        try {
            val (first, second) = runBlocking {
                val a = async(Dispatchers.IO) {
                    SecretValue.ofText(secret).use { ServerStarter(session.exec).start(it, ServerStartOptions(port = slow, timeout = 20.seconds)) }
                }
                delay(300)
                val b = async(Dispatchers.IO) {
                    SecretValue.ofText(secret).use { ServerStarter(other.exec).start(it, ServerStartOptions(port = fast, timeout = 20.seconds)) }
                }
                a.await() to b.await()
            }
            assertEquals(StartedServer("http://127.0.0.1:$slow", slow, record.resolve("pid.$slow").readText().trim().toLong()), first)
            assertEquals(StartedServer("http://127.0.0.1:$fast", fast, record.resolve("pid.$fast").readText().trim().toLong()), second)
            assertTrue(Procs.alive(first.pid) && Procs.alive(second.pid))
            assertEquals(
                listOf("server-${first.pid}.log", "server-${second.pid}.log", "server.state").sorted(),
                stateNames(),
            )
        } finally {
            other.close()
        }
    }

    @Test
    fun `logs of servers that are gone are cleaned up`() {
        stateDir.createDirectories()
        // Above Linux's largest pid_max, so no process can have this ID.
        stateDir.resolve("server-999999999.log").writeText("old")
        stateDir.resolve("start-999999999.log").writeText("old")
        givenFake("ok")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        assertEquals(listOf("server-${started.pid}.log", "server.state"), stateNames())
    }

    @Test
    fun `a directory at the state path is refused and the server stopped`() {
        val target = stateDir.resolve("server.state").createDirectories()
        givenFake("ok")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).exceptionOrNull()
        assertIs<ServerStartException.StateNotWritten>(failure)
        awaitGone(recordedPid()!!)
        assertTrue(Files.isDirectory(target))
        assertEquals(emptyList(), Files.list(target).use { it.toList() })
        assertFalse(stateNames().any { it.startsWith(".") }, "Temporary file left behind")
    }

    @Test
    fun `a symlink at the state path is refused and the server stopped`() {
        stateDir.createDirectories()
        val elsewhere = home.resolve("elsewhere")
        elsewhere.writeText("untouched")
        Files.createSymbolicLink(stateDir.resolve("server.state"), elsewhere)
        givenFake("ok")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).exceptionOrNull()
        assertIs<ServerStartException.StateNotWritten>(failure)
        awaitGone(recordedPid()!!)
        assertEquals("untouched", elsewhere.readText())
        assertTrue(Files.isSymbolicLink(stateDir.resolve("server.state")))
        assertFalse(stateNames().any { it.startsWith(".") }, "Temporary file left behind")
    }

    @Test
    fun `a server printing a huge ready line is refused and stopped`() {
        givenFake("huge")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).exceptionOrNull()
        assertIs<ServerStartException.UnexpectedOutput>(failure)
        awaitGone(recordedPid()!!)
        assertFalse(stateDir.resolve("server.state").exists())
    }

    @Test
    fun `a server that ignores TERM is killed when it is refused`() {
        givenFake("wide")
        home.resolve("ignore-term").writeText("")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 10.seconds)).exceptionOrNull()
        assertIs<ServerStartException.UnexpectedOutput>(failure)
        awaitGone(recordedPid()!!)
    }

    @Test
    fun `a server that ignores TERM is killed when it times out`() {
        givenFake("silent")
        home.resolve("ignore-term").writeText("")
        val failure = start(ServerStartOptions(port = freePort(), timeout = 2.seconds)).exceptionOrNull()
        assertIs<ServerStartException.Timeout>(failure)
        awaitGone(recordedPid()!!)
    }

    @Test
    fun `without setsid a refused server and its child are both stopped`() {
        server.hideSetsid()
        givenFake("wide")
        home.resolve("child").writeText("")
        val port = freePort()
        val failure = start(ServerStartOptions(port = port, timeout = 10.seconds)).exceptionOrNull()
        assertIs<ServerStartException.UnexpectedOutput>(failure)
        awaitGone(recordedPid()!!)
        awaitGone(record.resolve("pid-child.$port").readText().trim().toLong())
    }

    @Test
    fun `without setsid a timed-out server and its child are both stopped`() {
        server.hideSetsid()
        givenFake("silent")
        home.resolve("child").writeText("")
        val port = freePort()
        val failure = start(ServerStartOptions(port = port, timeout = 2.seconds)).exceptionOrNull()
        assertIs<ServerStartException.Timeout>(failure)
        awaitGone(recordedPid()!!)
        awaitGone(record.resolve("pid-child.$port").readText().trim().toLong())
    }

    @Test
    fun `without setsid a started server still outlives the session`() {
        server.hideSetsid()
        givenFake("ok")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        session.close()
        Thread.sleep(500)
        assertTrue(Procs.alive(started.pid), "The server died with the session")
    }

    @Test
    fun `a server that prints its ready line late still starts, every time`() {
        givenFake("ok")
        repeat(4) { round ->
            val port = freePort()
            home.resolve("delay.$port").writeText("0.3")
            val started = start(ServerStartOptions(port = port, timeout = 20.seconds)).getOrElse {
                throw AssertionError("Round $round failed", it)
            }
            assertEquals("http://127.0.0.1:$port", started.url)
            assertTrue(stateDir.resolve("server-${started.pid}.log").readText().contains("listening on"))
            assertFalse(stateNames().any { it.startsWith("start-") }, "A start log was left behind")
            StartTestServer.kill(started.pid)
            awaitGone(started.pid)
        }
    }

    @Test
    fun `finds opencode through a relative PATH entry`() {
        val relative = home.resolve("relbin")
        givenFake("ok", at = relative)
        server.usePath("relbin:/usr/local/bin:/usr/bin:/bin")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        assertEquals(relative.resolve("opencode").toString(), Procs.argv(started.pid)!![1])
    }

    @Test
    fun `finds opencode through an empty PATH entry, which means the current folder`() {
        givenFake("ok", at = home)
        server.usePath(":/usr/local/bin:/usr/bin:/bin")
        val started = start(ServerStartOptions(port = freePort(), timeout = 20.seconds)).getOrThrow()
        assertEquals(home.resolve("opencode").toString(), Procs.argv(started.pid)!![1])
    }

    @Test
    fun `a ready line written in two parts gives the whole port`() {
        givenFake("split")
        val started = start(ServerStartOptions(timeout = 20.seconds)).getOrThrow()
        assertEquals(4123, started.port)
        assertEquals("http://127.0.0.1:4123", started.url)
        assertEquals(ServerState(4123, started.pid), ServerState.parse(stateDir.resolve("server.state").readBytes()))
    }

    @Test
    fun `a ready line that never ends times out without a state file`() {
        givenFake("partial")
        val failure = start(ServerStartOptions(timeout = 3.seconds)).exceptionOrNull()
        assertIs<ServerStartException.Timeout>(failure)
        awaitGone(recordedPid()!!)
        assertFalse(stateDir.resolve("server.state").exists())
    }

    private fun awaitGone(pid: Long) {
        val started = TimeSource.Monotonic.markNow()
        while (Procs.alive(pid)) {
            if (started.elapsedNow() > 10.seconds) throw AssertionError("The server was not stopped")
            Thread.sleep(20)
        }
    }

    private companion object {
        val ACCEPT = HostKeyVerifier { _, _ -> HostKeyDecision.Accept }

        /** Records its PID, arguments and environment, then behaves as `$HOME/mode` says. */
        val FAKE_OPENCODE = """
            #!/bin/sh
            rec="${'$'}HOME/record"
            mkdir -p "${'$'}rec"
            echo ${'$'}${'$'} > "${'$'}rec/pid"
            printf '%s\n' "${'$'}@" > "${'$'}rec/argv"
            env > "${'$'}rec/env"
            mode=${'$'}(cat "${'$'}HOME/mode")
            port=4096
            while [ ${'$'}# -gt 0 ]; do
              case ${'$'}1 in --port) port=${'$'}2; shift ;; esac
              shift
            done
            echo ${'$'}${'$'} > "${'$'}rec/pid.${'$'}port"
            if [ -f "${'$'}HOME/delay.${'$'}port" ]; then sleep "${'$'}(cat "${'$'}HOME/delay.${'$'}port")"; fi
            if [ -f "${'$'}HOME/child" ]; then sleep 300 & echo ${'$'}! > "${'$'}rec/pid-child.${'$'}port"; fi
            case ${'$'}mode in
              ok) echo "opencode starting"; echo "server listening on http://127.0.0.1:${'$'}port" ;;
              wide) echo "server listening on http://0.0.0.0:${'$'}port" ;;
              inuse) echo "error: Failed to start server. Is port ${'$'}port in use? EADDRINUSE" >&2; exit 1 ;;
              exit) echo "fatal: no config" >&2; exit 3 ;;
              split) printf 'server listening on http://127.0.0.1:41'; sleep 1; printf '23\n' ;;
              partial) printf 'server listening on http://127.0.0.1:41' ;;
              huge) printf 'server listening on http://127.0.0.1:%s' "${'$'}port"; head -c 1048576 /dev/zero | tr '\000' x; echo ;;
              silent) ;;
            esac
            if [ -f "${'$'}HOME/ignore-term" ]; then trap '' TERM; else trap 'exit 0' TERM; fi
            while :; do sleep 1; done
        """.trimIndent() + "\n"
    }
}
