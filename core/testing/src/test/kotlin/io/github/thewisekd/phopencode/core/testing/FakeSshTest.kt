package io.github.thewisekd.phopencode.core.testing

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.HostKey
import io.github.thewisekd.phopencode.core.ssh.api.HostKeyDecision
import io.github.thewisekd.phopencode.core.ssh.api.SftpClient
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SftpWriteOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshConnectOptions
import io.github.thewisekd.phopencode.core.ssh.api.SshCredential
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.api.SshTarget
import io.github.thewisekd.phopencode.core.testing.ssh.FakeHostKeyVerifier
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcessEnd
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteFiles
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSftpClient
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshConnector
import io.github.thewisekd.phopencode.core.testing.ssh.FakeSshSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.function.ThrowingSupplier

/** What the SSH fakes promise beyond the contracts, and the knobs a test drives them with. */
class FakeSshTest {
    private val target = SshTarget("server.invalid", 22, "user")
    private val key = HostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
    private val accept = FakeHostKeyVerifier { _, _ -> HostKeyDecision.Accept }
    private val credential = SshCredential.Password("x".toCharArray())

    private fun files() = FakeRemoteFiles().apply { addDirectory("/srv/root") }

    private fun assertRejected(block: suspend () -> Unit) = runTest {
        assertEquals(SftpFailure.PathRejected, assertFailsWith<SshException.Sftp> { block() }.failure)
    }

    @Test
    fun `an unscripted command is a non-zero result, as in a shell`() = runTest {
        val result = FakeRemoteExec().run(ExecRequest("unknown"))
        assertEquals(127, result.exitCode)
    }

    @Test
    fun `the request is recorded, with a copy of stdin that outlives the caller's wipe`() = runTest {
        val exec = FakeRemoteExec()
        val stdin = byteArrayOf(1, 2, 3)
        exec.run(ExecRequest("command", stdin = stdin))
        stdin.fill(0)
        assertEquals("command", exec.executions.single().command)
        assertContentEquals(byteArrayOf(1, 2, 3), exec.executions.single().stdin)
    }

    @Test
    fun `the way a process ended is recorded`() = runTest {
        val exec = FakeRemoteExec().apply { scriptContractCommands() }
        exec.run(ExecRequest("unknown"))
        assertEquals(FakeProcessEnd.Exited, exec.executions.last().end)
        assertFailsWith<SshException.Timeout> {
            exec.run(ExecRequest(io.github.thewisekd.phopencode.core.testing.contract.EXEC_RUNS_ON))
        }
        assertEquals(FakeProcessEnd.Killed, exec.executions.last().end)
    }

    @Test
    fun `the default timeout applies`() = runTest {
        val exec = FakeRemoteExec().apply { on("sleep") { kotlinx.coroutines.delay(ExecRequest.DEFAULT_TIMEOUT * 2); exit(0) } }
        assertFailsWith<SshException.Timeout> { exec.run(ExecRequest("sleep")) }
    }

    @Test
    fun `a script can emit output chunk by chunk`() = runTest {
        val exec = FakeRemoteExec().apply {
            on("chunks") {
                stdout("a")
                stdout("b")
                exit(0)
            }
        }
        val events = exec.stream(ExecRequest("chunks")).toList()
        assertEquals(3, events.size)
        assertEquals("b", assertIs<ExecEvent.Stdout>(events[1]).bytes.decodeToString())
    }

    @Test
    fun `an injected failure reaches the next command only`() = runTest {
        val exec = FakeRemoteExec()
        exec.failNext(SshException.Disconnected())
        assertFailsWith<SshException.Disconnected> { exec.run(ExecRequest("x")) }
        exec.run(ExecRequest("x"))
    }

    @Test
    fun `the verifier records what it saw`() = runTest {
        val verifier = FakeHostKeyVerifier { _, _ -> HostKeyDecision.Reject }
        verifier.verify(target, key)
        assertEquals(listOf(target to key), verifier.verified)
        assertEquals(listOf(target to key), verifier.asked)
    }

    @Test
    fun `a known key belongs to one target`() = runTest {
        val verifier = FakeHostKeyVerifier { _, _ -> HostKeyDecision.Reject }
        verifier.trust(target, key)
        assertEquals(HostKeyDecision.Reject, verifier.verify(SshTarget("other.invalid", 22, "user"), key))
        assertEquals(1, verifier.asked.size)
    }

    @Test
    fun `a connector hands out its session, which the test can then drive`() = runTest {
        val connector = FakeSshConnector(key).apply { addServer(target) }
        val session = connector.connect(target, credential, accept)
        assertSame(connector.sessions.single(), session)
        assertEquals(1, connector.attempts.size)
        assertEquals(target, connector.attempts.single().target)
    }

    @Test
    fun `the record of an attempt keeps no credential`() = runTest {
        val connector = FakeSshConnector(key).apply { addServer(target) }
        val password = "secret".toCharArray()
        connector.connect(target, SshCredential.Password(password), accept)
        assertTrue("secret" !in connector.attempts.single().toString())
    }

    @Test
    fun `a connector with no server for a target cannot connect`() = runTest {
        assertFailsWith<SshException.ConnectionFailed> { FakeSshConnector(key).connect(target, credential, accept) }
    }

    @Test
    fun `an injected failure reaches the next connect, before any verification`() = runTest {
        val connector = FakeSshConnector(key).apply { addServer(target) }
        connector.failNext(SshException.ConnectionFailed())
        assertFailsWith<SshException.ConnectionFailed> { connector.connect(target, credential, accept) }
        assertEquals(emptyList(), accept.verified)
        connector.connect(target, credential, accept)
    }

    @Test
    fun `time with the verifier does not count against the connect timeout`() = runTest {
        val connector = FakeSshConnector(key).apply { addServer(target) }
        val slow = FakeHostKeyVerifier { _, _ ->
            kotlinx.coroutines.delay(SshConnectOptions().connectTimeout * 10)
            HostKeyDecision.Accept
        }
        connector.connect(target, credential, slow).close()
    }

    @Test
    fun `a tunnel carries bytes both ways and remembers its address`() = runTest {
        val session = FakeSshSession()
        val socket = session.socketFactory().createSocket()
        socket.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096))
        assertEquals(listOf("127.0.0.1" to 4096), session.tunnels)
        socket.getOutputStream().write(65)
        assertContentEquals(byteArrayOf(65), session.sockets.single().written())
        session.sockets.single().respond(byteArrayOf(66))
        assertEquals(66, socket.getInputStream().read())
    }

    @Test
    fun `a connected factory call opens the tunnel at once`() = runTest {
        val session = FakeSshSession()
        session.socketFactory().createSocket("127.0.0.1", 4096)
        assertEquals(listOf("127.0.0.1" to 4096), session.tunnels)
    }

    @Test
    fun `a socket fails after the session ended, and ends its reader`() = runTest {
        val session = FakeSshSession()
        val socket = session.socketFactory().createSocket()
        socket.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096))
        val input = socket.getInputStream()
        session.close()
        assertEquals(-1, input.read())
        assertFailsWith<IOException> { socket.getOutputStream().write(1) }
        val late = session.socketFactory().createSocket()
        assertFailsWith<IOException> { late.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096)) }
    }

    @Test
    fun `losing the connection ends its clients and sockets too`() = runTest {
        val session = FakeSshSession().apply { files.addDirectory("/srv/root") }
        val client = session.openSftp("/srv/root")
        val socket = session.socketFactory().createSocket()
        session.loseConnection()
        assertFailsWith<SshException.Disconnected> { client.list("") }
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a path that fails the rules sends no request`() = runTest {
        val client = FakeSftpClient("/srv/root", files())
        val before = client.requests
        assertFailsWith<SshException.Sftp> { client.stat("../x") }
        assertFailsWith<SshException.Sftp> { client.stat("/srv/other") }
        assertEquals(before, client.requests)
        client.stat("")
        assertEquals(before + 1, client.requests)
    }

    @Test
    fun `the path rules come before the connection state`() = runTest {
        val client = FakeSftpClient("/srv/root", files())
        client.disconnect()
        assertEquals(SftpFailure.PathRejected, assertFailsWith<SshException.Sftp> { client.stat("..") }.failure)
    }

    @Test
    fun `a closed client refuses calls with a plain failure`() = runTest {
        val client = FakeSftpClient("/srv/root", files())
        client.close()
        assertEquals(SftpFailure.Failed, assertFailsWith<SshException.Sftp> { client.list("") }.failure)
    }

    @Test
    fun `the temporary file is owner-only while it is written`() = runTest {
        val files = files()
        var modes = emptySet<Int>()
        val source = object : InputStream() {
            private var read = false

            override fun read(): Int {
                if (read) {
                    modes = files.names("/srv/root").map { files.mode("/srv/root/$it") }.toSet()
                    return -1
                }
                read = true
                return 1
            }
        }
        FakeSftpClient("/srv/root", files).writeAtomic("a.txt", source, SftpWriteOptions(permissions = 0b111_111_111))
        assertEquals(setOf(SftpWriteOptions.OWNER_ONLY), modes)
        assertEquals(0b111_111_111, files.mode("/srv/root/a.txt"))
    }

    @Test
    fun `readers see the old file until the write finishes`() = runTest {
        val files = files().apply { addFile("/srv/root/a.txt", "old".encodeToByteArray()) }
        var during: ByteArray? = null
        val source = object : InputStream() {
            private val bytes = ByteArrayInputStream("new".encodeToByteArray())

            override fun read(): Int {
                during = files.content("/srv/root/a.txt")
                return bytes.read()
            }
        }
        FakeSftpClient("/srv/root", files).writeAtomic("a.txt", source, SftpWriteOptions(overwrite = true))
        assertContentEquals("old".encodeToByteArray(), during)
        assertContentEquals("new".encodeToByteArray(), files.content("/srv/root/a.txt"))
    }

    @Test
    fun `a symlink loop fails instead of hanging`() = runTest {
        val files = files().apply {
            addSymlink("/srv/root/a", "b")
            addSymlink("/srv/root/b", "a")
        }
        val failure = assertFailsWith<SshException.Sftp> { FakeSftpClient("/srv/root", files).stat("a") }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a write through a dangling symlink creates its target`() = runTest {
        val files = files().apply { addSymlink("/srv/root/link", "target.txt") }
        FakeSftpClient("/srv/root", files).writeAtomic("link", ByteArrayInputStream(byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1), files.content("/srv/root/target.txt"))
    }

    @Test
    fun `the root itself cannot be deleted`() = runTest {
        val failure = assertFailsWith<SshException.Sftp> { FakeSftpClient("/srv/root", files()).delete("", recursive = true) }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a folder cannot move into itself`() = runTest {
        val files = files().apply { addDirectory("/srv/root/a") }
        val failure = assertFailsWith<SshException.Sftp> { FakeSftpClient("/srv/root", files).rename("a", "a/b") }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a file that has the temporary name survives a write`() = runTest {
        val files = files().apply { addFile("/srv/root/.a.txt.fake-1", byteArrayOf(7)) }
        FakeSftpClient("/srv/root", files).writeAtomic("a.txt", ByteArrayInputStream(byteArrayOf(1)))
        assertContentEquals(byteArrayOf(7), files.content("/srv/root/.a.txt.fake-1"))
        assertContentEquals(byteArrayOf(1), files.content("/srv/root/a.txt"))
    }

    @Test
    fun `a file that has the temporary name survives a failed write`() = runTest {
        val files = files().apply { addFile("/srv/root/.a.txt.fake-1", byteArrayOf(7)) }
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("source failed")
        }
        assertFailsWith<SshException.Sftp> { FakeSftpClient("/srv/root", files).writeAtomic("a.txt", source) }
        assertContentEquals(byteArrayOf(7), files.content("/srv/root/.a.txt.fake-1"))
    }

    @Test
    fun `an overwriting rename does not put a file over a folder`() = runTest {
        val files = files().apply {
            addFile("/srv/root/a.txt", byteArrayOf(1))
            addDirectory("/srv/root/folder")
        }
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).rename("a.txt", "folder", overwrite = true)
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertEquals(setOf("a.txt", "folder"), files.names("/srv/root"))
        assertEquals(emptySet(), files.names("/srv/root/folder"))
    }

    @Test
    fun `an overwriting rename does not put a folder over a file`() = runTest {
        val files = files().apply {
            addFile("/srv/root/a.txt", byteArrayOf(1))
            addDirectory("/srv/root/folder")
        }
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).rename("folder", "a.txt", overwrite = true)
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertContentEquals(byteArrayOf(1), files.content("/srv/root/a.txt"))
    }

    @Test
    fun `an overwriting rename does not put a folder over a symlink`() = runTest {
        val files = files().apply {
            addSymlink("/srv/root/link", "elsewhere")
            addDirectory("/srv/root/folder")
        }
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).rename("folder", "link", overwrite = true)
        }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a file put over a folder with content is a kind clash, not NotEmpty`() = runTest {
        val files = files().apply {
            addFile("/srv/root/a.txt", byteArrayOf(1))
            addFile("/srv/root/folder/deep.txt", byteArrayOf(2))
        }
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).rename("a.txt", "folder", overwrite = true)
        }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `an overwriting rename puts a folder over an empty folder`() = runTest {
        val files = files().apply {
            addFile("/srv/root/from/deep.txt", byteArrayOf(1))
            addDirectory("/srv/root/to")
        }
        FakeSftpClient("/srv/root", files).rename("from", "to", overwrite = true)
        assertContentEquals(byteArrayOf(1), files.content("/srv/root/to/deep.txt"))
        assertEquals(setOf("to"), files.names("/srv/root"))
    }

    @Test
    fun `a client opened while the connection ends does not stay usable`() {
        val session = FakeSshSession().apply { files.addDirectory("/srv/root") }
        var opened: Result<SftpClient>? = null
        val opener = thread(start = false) { opened = runCatching { runBlocking { session.openSftp("/srv/root") } } }
        val loser = thread(start = false) { session.loseConnection() }
        // openSftp reads the tree on its way, so holding the tree parks it between its two steps.
        synchronized(session.files) {
            opener.start()
            opener.awaitBlockedOrDone()
            loser.start()
            loser.awaitBlockedOrDone()
        }
        opener.join()
        loser.join()
        val client = opened?.getOrNull()
        if (client == null) {
            assertIs<SshException.Disconnected>(opened?.exceptionOrNull())
        } else {
            runBlocking { assertFailsWith<SshException.Disconnected> { client.list("") } }
        }
    }

    private fun connectedSocket(session: FakeSshSession) = session.socketFactory().createSocket().apply {
        connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096))
    }

    private fun <T> withinTime(block: () -> T): T =
        Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), ThrowingSupplier { block() })

    @Test
    fun `a bulk read returns what has arrived instead of waiting for a full buffer`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        session.sockets.single().respond(byteArrayOf(1, 2))
        val buffer = ByteArray(8)
        assertEquals(2, withinTime { socket.getInputStream().read(buffer, 0, buffer.size) })
        assertContentEquals(byteArrayOf(1, 2), buffer.copyOf(2))
    }

    @Test
    fun `a bulk read honours the offset and the length`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        session.sockets.single().respond(byteArrayOf(1, 2, 3))
        val buffer = ByteArray(4)
        assertEquals(2, withinTime { socket.getInputStream().read(buffer, 1, 2) })
        assertContentEquals(byteArrayOf(0, 1, 2, 0), buffer)
        assertEquals(3, withinTime { socket.getInputStream().read() })
    }

    @Test
    fun `a bulk read of nothing returns at once`() {
        val socket = connectedSocket(FakeSshSession())
        assertEquals(0, withinTime { socket.getInputStream().read(ByteArray(4), 0, 0) })
    }

    @Test
    fun `a bulk read blocks for the first byte`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val buffer = ByteArray(4)
        var count = 0
        val reader = thread { count = socket.getInputStream().read(buffer, 0, buffer.size) }
        while (reader.state != Thread.State.WAITING) Thread.onSpinWait()
        session.sockets.single().respond(byteArrayOf(9))
        withinTime { reader.join() }
        assertEquals(1, count)
    }

    @Test
    fun `a bulk read gives the last bytes, then the end of the stream, again and again`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val input = socket.getInputStream()
        session.sockets.single().respond(byteArrayOf(1))
        session.close()
        val buffer = ByteArray(4)
        assertEquals(1, withinTime { input.read(buffer, 0, buffer.size) })
        assertEquals(-1, withinTime { input.read(buffer, 0, buffer.size) })
        assertEquals(-1, withinTime { input.read(buffer, 0, buffer.size) })
        assertEquals(-1, withinTime { input.read() })
    }

    /** Gives two bytes, and runs [action] just before the read that follows the first. */
    private fun sourceThat(action: () -> Unit, finalRead: Boolean) = object : InputStream() {
        private var reads = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            return when {
                reads == 1 -> 1.also { buffer[offset] = 1 }
                reads == 2 && !finalRead -> {
                    action()
                    buffer[offset] = 2
                    1
                }
                reads == 2 -> {
                    action()
                    -1
                }
                else -> -1
            }
        }

        override fun read(): Int = error("Bulk reads only")
    }

    private fun assertTemporaryGone(moveDuring: (FakeSftpClient) -> Unit, finalRead: Boolean, kept: String) = runTest {
        val files = files().apply { addFile("/srv/root/dir/a.txt", "old".encodeToByteArray()) }
        val writer = FakeSftpClient("/srv/root", files)
        val mover = FakeSftpClient("/srv/root", files)
        val source = sourceThat({ moveDuring(mover) }, finalRead)
        val failure = assertFailsWith<SshException.Sftp> {
            writer.writeAtomic("dir/a.txt", source, SftpWriteOptions(overwrite = true))
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertEquals(setOf("a.txt"), files.names(kept))
        assertContentEquals("old".encodeToByteArray(), files.content("$kept/a.txt"))
    }

    @Test
    fun `a folder renamed during a write fails it and leaves no temporary file`() {
        assertTemporaryGone({ runBlocking { it.rename("dir", "moved") } }, finalRead = false, kept = "/srv/root/moved")
    }

    @Test
    fun `a folder renamed at the end of a write fails it and leaves no temporary file`() {
        assertTemporaryGone({ runBlocking { it.rename("dir", "moved") } }, finalRead = true, kept = "/srv/root/moved")
    }

    @Test
    fun `a parent renamed during a write leaves no temporary file deeper down`() = runTest {
        val files = files().apply { addFile("/srv/root/top/dir/a.txt", byteArrayOf(1)) }
        val mover = FakeSftpClient("/srv/root", files)
        val source = sourceThat({ runBlocking { mover.rename("top", "moved") } }, finalRead = false)
        assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).writeAtomic("top/dir/a.txt", source, SftpWriteOptions(overwrite = true))
        }
        assertEquals(setOf("a.txt"), files.names("/srv/root/moved/dir"))
    }

    @Test
    fun `a folder replaced during a write does not take the write's bytes`() = runTest {
        val files = files().apply { addFile("/srv/root/dir/a.txt", "old".encodeToByteArray()) }
        val source = sourceThat({
            runBlocking { FakeSftpClient("/srv/root", files).rename("dir", "moved") }
            files.addDirectory("/srv/root/dir")
        }, finalRead = false)
        assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).writeAtomic("dir/a.txt", source, SftpWriteOptions(overwrite = true))
        }
        assertEquals(emptySet(), files.names("/srv/root/dir"))
        assertEquals(setOf("a.txt"), files.names("/srv/root/moved"))
    }

    @Test
    fun `a temporary file deleted during a write fails it without a raw error`() = runTest {
        val files = files()
        val cleaner = FakeSftpClient("/srv/root", files)
        val source = sourceThat({
            runBlocking { files.names("/srv/root").forEach { cleaner.delete(it) } }
        }, finalRead = false)
        val failure = assertFailsWith<SshException.Sftp> { FakeSftpClient("/srv/root", files).writeAtomic("a.txt", source) }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertEquals(emptySet(), files.names("/srv/root"))
    }

    private fun aliased() = files().apply {
        addSymlink("/srv/root/up", "..")
        addFile("/srv/root/a.txt", byteArrayOf(1))
    }

    @Test
    fun `a symlink alias cannot delete the root`() = runTest {
        val files = aliased()
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).delete("up/root", recursive = true)
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertTrue(files.exists("/srv/root/a.txt"))
    }

    @Test
    fun `a symlink alias cannot move the root`() = runTest {
        val files = aliased()
        val failure = assertFailsWith<SshException.Sftp> {
            FakeSftpClient("/srv/root", files).rename("up/root", "up/root/inside")
        }
        assertEquals(SftpFailure.Failed, failure.failure)
        assertTrue(files.exists("/srv/root/a.txt"))
    }

    @Test
    fun `nothing can be renamed over the root, by name or through an alias`() = runTest {
        val files = aliased().apply { addDirectory("/srv/root/folder") }
        val client = FakeSftpClient("/srv/root", files)
        listOf("", "/srv/root", "up/root").forEach { path ->
            val failure = assertFailsWith<SshException.Sftp> { client.rename("folder", path, overwrite = true) }
            assertEquals(SftpFailure.Failed, failure.failure)
        }
        assertTrue(files.exists("/srv/root/a.txt"))
    }

    @Test
    fun `a root of slash is not deleted or moved either`() = runTest {
        val client = FakeSftpClient("/", files())
        listOf<suspend () -> Unit>(
            { client.delete("/", recursive = true) },
            { client.rename("/", "/srv/moved") },
            { client.rename("/srv", "/", overwrite = true) },
        ).forEach { call ->
            assertEquals(SftpFailure.Failed, assertFailsWith<SshException.Sftp> { call() }.failure)
        }
    }

    @Test
    fun `a root that is itself a symlink works for changes`() = runTest {
        val files = files().apply { addSymlink("/srv/alias", "root") }
        val client = FakeSftpClient("/srv/alias", files)
        client.writeAtomic("a.txt", ByteArrayInputStream(byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1), files.content("/srv/root/a.txt"))
        val failure = assertFailsWith<SshException.Sftp> { client.delete("", recursive = true) }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a file cannot be the root of an SFTP client`() = runTest {
        val session = FakeSshSession().apply { files.addFile("/srv/file.txt", byteArrayOf(1)) }
        val failure = assertFailsWith<SshException.Sftp> { session.openSftp("/srv/file.txt") }
        assertEquals(SftpFailure.Failed, failure.failure)
    }

    @Test
    fun `a symlink to a folder can be the root, and a dangling one cannot`() = runTest {
        val session = FakeSshSession().apply {
            files.addDirectory("/srv/root")
            files.addSymlink("/srv/alias", "root")
            files.addSymlink("/srv/dangling", "missing")
        }
        session.openSftp("/srv/alias")
        val failure = assertFailsWith<SshException.Sftp> { session.openSftp("/srv/dangling") }
        assertEquals(SftpFailure.NotFound, failure.failure)
    }

    @Test
    fun `a read gives up after the socket timeout, and the socket stays usable`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        socket.soTimeout = 50
        assertEquals(50, socket.soTimeout)
        assertFailsWith<SocketTimeoutException> { withinTime { socket.getInputStream().read() } }
        session.sockets.single().respond(byteArrayOf(5))
        assertEquals(5, withinTime { socket.getInputStream().read() })
    }

    @Test
    fun `a bulk read gives up after the socket timeout, and the socket stays usable`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val buffer = ByteArray(4)
        socket.soTimeout = 50
        assertFailsWith<SocketTimeoutException> { withinTime { socket.getInputStream().read(buffer, 0, 4) } }
        session.sockets.single().respond(byteArrayOf(5))
        assertEquals(1, withinTime { socket.getInputStream().read(buffer, 0, 4) })
    }

    @Test
    fun `a socket timeout refuses a closed socket and a negative value`() {
        val socket = FakeSshSession().socketFactory().createSocket()
        assertFailsWith<IllegalArgumentException> { socket.soTimeout = -1 }
        socket.close()
        assertFailsWith<SocketException> { socket.soTimeout = 10 }
    }

    @Test
    fun `an end of stream beats the timeout`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val input = socket.getInputStream()
        socket.soTimeout = 50
        session.close()
        assertEquals(-1, withinTime { input.read() })
    }

    @Test
    fun `connecting a connected socket fails and opens no second tunnel`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        assertFailsWith<SocketException> { socket.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096)) }
        assertEquals(1, session.tunnels.size)
    }

    @Test
    fun `a connect that failed does not count as connected`() {
        val session = FakeSshSession()
        val socket = session.socketFactory().createSocket()
        session.loseConnection()
        assertFailsWith<SocketException> { socket.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096)) }
        assertFalse(socket.isConnected)
    }

    @Test
    fun `cancelling from the final progress callback stops a download from succeeding`() = runTest {
        val files = files().apply { addFile("/srv/root/a.txt", byteArrayOf(1)) }
        val client = FakeSftpClient("/srv/root", files)
        var total: Long? = null
        val job = launch {
            val self = coroutineContext.job
            total = client.download("a.txt", ByteArrayOutputStream()) { self.cancel() }
        }
        job.join()
        assertNull(total)
    }

    @Test
    fun `a connection lost in the final progress callback fails the download`() = runTest {
        val files = files().apply { addFile("/srv/root/a.txt", byteArrayOf(1)) }
        val client = FakeSftpClient("/srv/root", files)
        assertFailsWith<SshException.Disconnected> {
            client.download("a.txt", ByteArrayOutputStream()) { client.disconnect() }
        }
    }

    @Test
    fun `closing the input stream closes the socket`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val input = socket.getInputStream()
        input.close()
        assertTrue(socket.isClosed)
        assertEquals(-1, withinTime { input.read() })
    }

    @Test
    fun `an unconnected socket has no streams`() {
        val socket = FakeSshSession().socketFactory().createSocket()
        assertEquals("Socket is not connected", assertFailsWith<SocketException> { socket.getInputStream() }.message)
        assertEquals("Socket is not connected", assertFailsWith<SocketException> { socket.getOutputStream() }.message)
    }

    @Test
    fun `a closed socket has no streams`() {
        val socket = connectedSocket(FakeSshSession())
        socket.close()
        assertEquals("Socket is closed", assertFailsWith<SocketException> { socket.getInputStream() }.message)
        assertEquals("Socket is closed", assertFailsWith<SocketException> { socket.getOutputStream() }.message)
    }

    @Test
    fun `an address that is not an internet address is refused, and the socket can still connect`() {
        val session = FakeSshSession()
        val socket = session.socketFactory().createSocket()
        val failure = assertFailsWith<IllegalArgumentException> { socket.connect(object : SocketAddress() {}) }
        assertEquals("Unsupported address type", failure.message)
        assertFalse(socket.isConnected)
        socket.connect(InetSocketAddress.createUnresolved("127.0.0.1", 4096))
        assertTrue(socket.isConnected)
        assertEquals(1, session.tunnels.size)
    }

    @Test
    fun `closing the output stream closes the socket`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        socket.getOutputStream().close()
        assertTrue(socket.isClosed)
        assertFailsWith<IOException> { socket.getOutputStream().write(1) }
    }

    @Test
    fun `closing the streams and the socket again is harmless`() {
        val session = FakeSshSession()
        val socket = connectedSocket(session)
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        input.close()
        input.close()
        output.close()
        socket.close()
        session.close()
        assertTrue(socket.isClosed)
        assertEquals(-1, withinTime { input.read() })
        assertEquals(-1, withinTime { input.read() })
    }

    @Test
    fun `a relative root is rejected by openSftp`() = runTest {
        assertEquals(
            SftpFailure.PathRejected,
            assertFailsWith<SshException.Sftp> { FakeSshSession().openSftp("relative") }.failure,
        )
    }

    @Test
    fun `an injected failure reaches the next SFTP call only`() = runTest {
        val client = FakeSftpClient("/srv/root", files())
        client.failNext(SshException.Sftp(SftpFailure.Failed))
        assertFailsWith<SshException.Sftp> { client.list("") }
        assertEquals(emptyList(), client.list(""))
    }

    @Test
    fun `a missing content is null`() {
        assertNull(files().content("/srv/root/missing"))
    }

    @Test
    fun `all clients of a session share its files`() = runTest {
        val session = FakeSshSession().apply { files.addDirectory("/srv/root") }
        session.openSftp("/srv/root").writeAtomic("a.txt", ByteArrayInputStream(byteArrayOf(1)))
        assertContentEquals(byteArrayOf(1), session.openSftp("/srv/root").read("a.txt", 10))
    }

    @Test
    fun `closing a session is safe after losing the connection`() = runTest {
        val session = FakeSshSession()
        session.loseConnection()
        session.close()
    }
}
