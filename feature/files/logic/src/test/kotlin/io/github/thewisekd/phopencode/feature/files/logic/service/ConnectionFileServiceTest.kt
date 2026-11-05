package io.github.thewisekd.phopencode.feature.files.logic.service

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.files.ContentMatch
import io.github.thewisekd.phopencode.core.files.ContentQuery
import io.github.thewisekd.phopencode.core.files.FileError
import io.github.thewisekd.phopencode.core.files.FileException
import io.github.thewisekd.phopencode.core.files.FileService
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.NotConnectedException
import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentHit
import io.github.thewisekd.phopencode.feature.files.logic.contract.ContentSearchTool
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionFileServiceTest {
    private suspend fun failure(block: suspend () -> Unit): FilesFailure = assertFailsWith<FilesException> { block() }.failure

    private suspend fun TestScope.connected(profile: io.github.thewisekd.phopencode.core.model.ServerProfile = SERVICE_PROFILE): ServiceConnection {
        val connection = ServiceConnection(backgroundScope)
        connection.connect(profile)
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        connection.sessions.last().files.addDirectory(ROOT)
        return connection
    }

    /** An adapter whose every call gets [scripted]; what the factory was given lands in [opened]. */
    private class Scripted(connection: ServiceConnection) {
        val scripted = ScriptedFileService(ROOT)
        val opened = mutableListOf<Opened>()
        val adapter = ConnectionFileService(connection.manager) { sftp, exec, fs ->
            synchronized(opened) { opened += Opened(sftp, exec, fs) }
            scripted
        }
    }

    private fun allCalls(service: ConnectionFileService): List<suspend () -> Unit> = listOf(
        { service.list(ROOT, ROOT) },
        { service.stat(ROOT, "a.txt") },
        { service.read(ROOT, "a.txt", maxBytes = 10) },
        { service.write(ROOT, "a.txt", byteArrayOf(1)) },
        { service.delete(ROOT, "a.txt", recursive = false) },
        { service.move(ROOT, "a.txt", "b.txt") },
        { service.createFolder(ROOT, "docs") },
        { service.upload(ROOT, "a.txt", ByteArrayInputStream(byteArrayOf(1)), overwrite = false) {} },
        { service.download(ROOT, "a.txt", ByteArrayOutputStream()) {} },
        { service.findNames(ROOT, "main", limit = 10) },
        { service.searchContent(ROOT, "main", ContentSearchTool.Ripgrep, limit = 10) },
    )

    @Test
    fun `without a connection every port fails as not connected`() = runTest {
        val connection = ServiceConnection(backgroundScope)
        val opened = mutableListOf<Opened>()
        val service = ConnectionFileService(connection.manager) { sftp, exec, fs ->
            opened += Opened(sftp, exec, fs)
            ScriptedFileService(ROOT)
        }

        allCalls(service).forEach { call -> assertEquals(FilesFailure.NotConnected, failure(call)) }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the ports reach the connected server's files`() = runTest {
        val connection = connected()
        val files = connection.sessions.last().files
        files.addFile("$ROOT/a.txt", "merhaba".encodeToByteArray())
        val service = ConnectionFileService(connection.manager)

        assertEquals(setOf("a.txt"), service.list(ROOT, ROOT).map { it.name }.toSet())
        assertEquals(FileEntryType.File, service.stat(ROOT, "a.txt").type)
        assertContentEquals("merhaba".encodeToByteArray(), service.read(ROOT, "a.txt", maxBytes = 100))

        val written = service.write(ROOT, "a.txt", "yeni içerik".encodeToByteArray())
        assertEquals("$ROOT/a.txt", written.path)
        assertContentEquals("yeni içerik".encodeToByteArray(), files.content("$ROOT/a.txt"))

        service.createFolder(ROOT, "docs")
        service.move(ROOT, "a.txt", "docs/b.txt")
        assertEquals(setOf("b.txt"), files.names("$ROOT/docs"))

        service.upload(ROOT, "docs/c.txt", ByteArrayInputStream(byteArrayOf(4, 5)), overwrite = false) {}
        val sink = ByteArrayOutputStream()
        assertEquals(2, service.download(ROOT, "docs/c.txt", sink) {})
        assertContentEquals(byteArrayOf(4, 5), sink.toByteArray())

        service.delete(ROOT, "docs", recursive = true)
        assertFalse(files.exists("$ROOT/docs"))
    }

    @Test
    fun `a file service failure reaches the port mapped`() = runTest {
        val connection = connected()
        val service = ConnectionFileService(connection.manager)

        assertEquals(FilesFailure.NotFound, failure { service.stat(ROOT, "missing.txt") })
        assertEquals(FilesFailure.PathRejected, failure { service.stat(ROOT, "../etc/passwd") })
        assertEquals(FilesFailure.NotFound, failure { service.list("/srv/missing", "/srv/missing") }, "the SFTP open's own failure")
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
    }

    @Test
    fun `write stats the file afterwards and returns its new modification time`() = runTest {
        val scripted = Scripted(connected())

        val entry = scripted.adapter.write(ROOT, "a.txt", byteArrayOf(1, 2, 3))

        assertEquals(listOf("write", "stat"), scripted.scripted.calls)
        assertEquals(scripted.scripted.statEntry, entry)
        assertTrue(scripted.scripted.lastWriteOptions!!.overwrite, "the editor replaces the file")
    }

    @Test
    fun `upload passes the overwrite choice on`() = runTest {
        val scripted = Scripted(connected())

        scripted.adapter.upload(ROOT, "a.txt", ByteArrayInputStream(byteArrayOf(1)), overwrite = false) {}
        assertFalse(scripted.scripted.lastWriteOptions!!.overwrite)
        scripted.adapter.upload(ROOT, "a.txt", ByteArrayInputStream(byteArrayOf(1)), overwrite = true) {}
        assertTrue(scripted.scripted.lastWriteOptions!!.overwrite)
    }

    @Test
    fun `each call gets its own SFTP client at the directory, on the connection's session, closed afterwards`() = runTest {
        val connection = connected()
        val scripted = Scripted(connection)

        scripted.adapter.stat(ROOT, "a.txt")
        scripted.scripted.failure = FileException(FileError.NotFound)
        failure { scripted.adapter.stat(ROOT, "a.txt") }

        assertEquals(2, scripted.opened.size)
        assertNotEquals(scripted.opened[0].sftp, scripted.opened[1].sftp)
        scripted.opened.forEach { opened ->
            assertEquals(ROOT, opened.sftp.root)
            assertSame(connection.sessions.single().exec, opened.exec)
            assertSame(connection.clients.single().fs, opened.fs)
            assertFailsWith<SshException> { opened.sftp.list("") }
        }
    }

    @Test
    fun `a cancelled call closes its SFTP client and stays a cancellation`() = runTest {
        val scripted = Scripted(connected())
        scripted.scripted.gate = CompletableDeferred()

        val call = async { scripted.adapter.list(ROOT, ROOT) }
        runCurrent()
        assertEquals(listOf("list"), scripted.scripted.calls)
        call.cancel()
        runCurrent()

        assertTrue(call.isCancelled)
        assertFailsWith<CancellationException> { call.await() }
        assertFailsWith<SshException> { scripted.opened.single().sftp.list("") }
    }

    @Test
    fun `a cancellation from the service is never wrapped`() = runTest {
        val scripted = Scripted(connected())
        scripted.scripted.failure = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> { scripted.adapter.stat(ROOT, "a.txt") }

        assertEquals("stopped", thrown.message)
        assertFailsWith<SshException> { scripted.opened.single().sftp.list("") }
    }

    @Test
    fun `a new generation during a call fails it as not connected`() = runTest {
        val connection = connected()
        val scripted = Scripted(connection)
        scripted.scripted.gate = CompletableDeferred()
        val first = assertIs<ConnectionState.Connected>(connection.manager.state.value)

        val call = async { runCatching { scripted.adapter.list(ROOT, ROOT) } }
        runCurrent()
        connection.manager.reconnect()
        runCurrent()
        connection.manager.reconnect()
        runCurrent()
        val second = assertIs<ConnectionState.Connected>(connection.manager.state.value)
        assertNotEquals(first.generation, second.generation)
        scripted.scripted.gate!!.complete(Unit)
        runCurrent()

        assertEquals(FilesFailure.NotConnected, assertIs<FilesException>(call.await().exceptionOrNull()).failure)
        assertEquals(1, scripted.opened.size, "nothing ran on the new connection")
        assertSame(connection.sessions.first().exec, scripted.opened.single().exec)
    }

    @Test
    fun `another server during a call fails it as not connected`() = runTest {
        val connection = connected()
        val scripted = Scripted(connection)
        scripted.scripted.gate = CompletableDeferred()

        val call = async { runCatching { scripted.adapter.searchContent(ROOT, "main", ContentSearchTool.Ripgrep, 10) } }
        runCurrent()
        connection.connect(OTHER_PROFILE)
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        scripted.scripted.gate!!.complete(Unit)
        runCurrent()

        assertEquals(FilesFailure.NotConnected, assertIs<FilesException>(call.await().exceptionOrNull()).failure)
        assertEquals(1, scripted.opened.size)
        assertSame(connection.sessions.first().exec, scripted.opened.single().exec)
    }

    @Test
    fun `a server connected between the bind and the SFTP open is never touched`() = runTest {
        val connection = connected()
        val scripted = Scripted(connection)
        connection.sftpGate = CompletableDeferred()

        val call = async { runCatching { scripted.adapter.delete(ROOT, "a.txt", recursive = true) } }
        runCurrent()
        connection.connect(OTHER_PROFILE)
        runCurrent()
        val other = assertIs<ConnectionState.Connected>(connection.manager.state.value)
        assertEquals(OTHER_PROFILE.id, other.server.profileId)
        connection.sessions.last().files.addDirectory(ROOT)
        connection.sessions.last().files.addFile("$ROOT/a.txt", byteArrayOf(1))
        connection.sftpGate!!.complete(Unit)
        runCurrent()

        assertEquals(FilesFailure.NotConnected, assertIs<FilesException>(call.await().exceptionOrNull()).failure)
        assertTrue(scripted.opened.isEmpty(), "no file service was built")
        assertEquals(listOf<Any>(connection.sessions.first()), connection.sftpOpens.map { it as Any })
        assertTrue(connection.sessions.last().files.exists("$ROOT/a.txt"), "the new server's file stays")
        assertTrue(connection.clients.last().control.calls.none { it.startsWith("fs.") })
    }

    @Test
    fun `a dropped connection during a call fails it as not connected`() = runTest {
        val connection = connected()
        val scripted = Scripted(connection)
        scripted.scripted.gate = CompletableDeferred()

        val call = async { runCatching { scripted.adapter.download(ROOT, "a.txt", ByteArrayOutputStream()) {} } }
        runCurrent()
        connection.sessions.single().loseConnection()
        runCurrent()

        assertEquals(FilesFailure.NotConnected, assertIs<FilesException>(call.await().exceptionOrNull()).failure)
    }

    @Test
    fun `NotConnectedException maps to not connected`() = runTest {
        val scripted = Scripted(connected())
        scripted.scripted.failure = NotConnectedException()

        assertEquals(FilesFailure.NotConnected, failure { scripted.adapter.stat(ROOT, "a.txt") })
    }

    @Test
    fun `every file error maps to its files failure`() = runTest {
        val expected = listOf(
            FileError.NotFound to FilesFailure.NotFound,
            FileError.PermissionDenied to FilesFailure.PermissionDenied,
            FileError.AlreadyExists to FilesFailure.AlreadyExists,
            FileError.NotEmpty to FilesFailure.NotEmpty,
            FileError.PathRejected to FilesFailure.PathRejected,
            FileError.TooLarge(sizeBytes = 10, limitBytes = 5) to FilesFailure.TooLarge,
            FileError.TooLarge(sizeBytes = null, limitBytes = 5) to FilesFailure.TooLarge,
            FileError.NotAFile to FilesFailure.Rejected,
            FileError.NotADirectory to FilesFailure.Rejected,
            FileError.RootProtected to FilesFailure.Rejected,
            FileError.MoveIntoItself to FilesFailure.Rejected,
            FileError.Conflict to FilesFailure.Rejected,
            FileError.SearchUnavailable to FilesFailure.ToolMissing,
            FileError.SearchFailed to FilesFailure.ServerFailed,
            FileError.Failed to FilesFailure.ServerFailed,
            FileError.Api(ApiError.Forbidden) to FilesFailure.PermissionDenied,
            FileError.Api(ApiError.Network) to FilesFailure.Unreachable,
            FileError.Api(ApiError.Closed) to FilesFailure.NotConnected,
            FileError.Disconnected to FilesFailure.NotConnected,
            FileError.Timeout to FilesFailure.Unreachable,
        )
        val every = FileError::class.java.declaredClasses.filter { FileError::class.java.isAssignableFrom(it) }.toSet()
        assertEquals(every, expected.map { it.first.javaClass }.toSet(), "every FileError case is covered")

        val scripted = Scripted(connected())
        for ((error, want) in expected) {
            scripted.scripted.failure = FileException(error)
            assertEquals(want, failure { scripted.adapter.stat(ROOT, "a.txt") }, "$error")
            assertEquals(want, error.toFailure(), "$error")
        }
    }

    @Test
    fun `raw SSH and API failures map through FilesFailure`() = runTest {
        val scripted = Scripted(connected())
        val cases = listOf(
            SshException.ConnectionFailed() to FilesFailure.Unreachable,
            SshException.Timeout() to FilesFailure.Unreachable,
            SshException.OutputLimitExceeded(10) to FilesFailure.TooLarge,
            SshException.Sftp(SftpFailure.PermissionDenied) to FilesFailure.PermissionDenied,
            ApiException(ApiError.NotFound) to FilesFailure.NotFound,
            IOException("local sink") to FilesFailure.Unknown,
        )

        for ((thrown, expected) in cases) {
            scripted.scripted.failure = thrown
            val mapped = assertFailsWith<FilesException> { scripted.adapter.stat(ROOT, "a.txt") }
            assertEquals(expected, mapped.failure, "$thrown")
            assertEquals(null, mapped.cause, "no cause that might quote a path")
        }
    }

    @Test
    fun `content search ignores the tool and clamps the limit`() = runTest {
        val scripted = Scripted(connected())
        scripted.scripted.matches = listOf(ContentMatch("$ROOT/main.kt", 3, "fun main() {}"))

        for (tool in ContentSearchTool.entries) {
            val hits = scripted.adapter.searchContent(ROOT, "-n \$(main)", tool, limit = 7)
            assertEquals(listOf(ContentHit("$ROOT/main.kt", 3, "fun main() {}")), hits)
            val query = scripted.scripted.lastQuery!!
            assertEquals("-n \$(main)", query.text)
            assertFalse(query.regex)
            assertEquals(7, query.maxResults)
        }
        for ((limit, sent) in listOf(0 to 1, -5 to 1, 5000 to 5000, 10_000 to ContentQuery.MAX_RESULTS)) {
            scripted.adapter.searchContent(ROOT, "main", ContentSearchTool.Grep, limit)
            assertEquals(sent, scripted.scripted.lastQuery!!.maxResults, "limit $limit")
        }
    }

    @Test
    fun `an unsearchable content query is rejected before anything is opened`() = runTest {
        val scripted = Scripted(connected())

        assertEquals(FilesFailure.Rejected, failure { scripted.adapter.searchContent(ROOT, "", ContentSearchTool.Ripgrep, 10) })
        assertEquals(FilesFailure.Rejected, failure { scripted.adapter.searchContent(ROOT, "a\nb", ContentSearchTool.Ripgrep, 10) })
        assertTrue(scripted.opened.isEmpty())
    }

    @Test
    fun `a search without the tools fails as tool missing`() = runTest {
        val scripted = Scripted(connected())
        scripted.scripted.failure = FileException(FileError.SearchUnavailable)

        assertEquals(FilesFailure.ToolMissing, failure { scripted.adapter.searchContent(ROOT, "main", ContentSearchTool.Ripgrep, 10) })
    }

    @Test
    fun `name search returns the entries and clamps the limit`() = runTest {
        val scripted = Scripted(connected())
        val found = listOf(FileEntry("$ROOT/src/main.kt", FileEntryType.File))
        scripted.scripted.names = found

        assertEquals(found, scripted.adapter.findNames(ROOT, "main", limit = 11))
        assertEquals(11, scripted.scripted.lastNameLimit)
        scripted.adapter.findNames(ROOT, "main", limit = 0)
        assertEquals(1, scripted.scripted.lastNameLimit)
        scripted.adapter.findNames(ROOT, "main", limit = 50_000)
        assertEquals(FileService.MAX_NAME_RESULTS, scripted.scripted.lastNameLimit)
    }
}
