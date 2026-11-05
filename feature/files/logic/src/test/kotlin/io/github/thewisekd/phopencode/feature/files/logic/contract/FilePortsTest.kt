package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"

@OptIn(ExperimentalCoroutinesApi::class)
class FilePortsTest {
    private suspend fun failure(block: suspend () -> Unit): FilesFailure = assertFailsWith<FilesException> { block() }.failure

    @Test
    fun `without a file service every call fails as unsupported and touches nothing`() = runTest {
        val sink = ByteArrayOutputStream()
        val calls: List<suspend () -> Unit> = listOf(
            { NoFileService.list(ROOT, ROOT) },
            { NoFileService.stat(ROOT, "a.txt") },
            { NoFileService.read(ROOT, "a.txt", maxBytes = 10) },
            { NoFileService.write(ROOT, "a.txt", byteArrayOf(1)) },
            { NoFileService.delete(ROOT, "a.txt", recursive = false) },
            { NoFileService.move(ROOT, "a.txt", "b.txt") },
            { NoFileService.createFolder(ROOT, "docs") },
            { NoFileService.upload(ROOT, "a.txt", ByteArrayInputStream(byteArrayOf(1)), overwrite = false) {} },
            { NoFileService.download(ROOT, "a.txt", sink) {} },
            { NoFileService.findNames(ROOT, "main", limit = 10) },
            { NoFileService.searchContent(ROOT, "main", ContentSearchTool.Ripgrep, limit = 10) },
        )

        calls.forEach { call -> assertEquals(FilesFailure.Unsupported, failure(call)) }
        assertEquals(0, sink.size())
    }

    private suspend fun TestScope.connected(arrange: (FakeOpencodeClient) -> Unit = {}): FilesConnection {
        val connection = FilesConnection(backgroundScope)
        connection.arrange = arrange
        connection.connect()
        runCurrent()
        assertIs<ConnectionState.Connected>(connection.manager.state.value)
        return connection
    }

    @Test
    fun `git changes come from the active connection's server for the folder`() = runTest {
        val changed = listOf(
            VcsFile("src/main.kt", VcsFileStatus.Modified),
            VcsFile("notes yeni.md", VcsFileStatus.Untracked),
        )
        val connection = connected { it.vcs.setStatus(Location(ROOT), changed) }

        assertEquals(changed, ConnectionGitChanges(connection.manager).changes(ROOT))
        assertTrue(ConnectionGitChanges(connection.manager).changes("/srv/other").isEmpty())
    }

    @Test
    fun `git changes need a connection`() = runTest {
        val connection = FilesConnection(backgroundScope)

        assertEquals(FilesFailure.NotConnected, failure { ConnectionGitChanges(connection.manager).changes(ROOT) })
    }

    @Test
    fun `a failed git status maps to a files failure`() = runTest {
        val connection = connected()
        connection.lastClient().control.failNext(ApiError.Network)

        assertEquals(FilesFailure.Unreachable, failure { ConnectionGitChanges(connection.manager).changes(ROOT) })
    }
}
