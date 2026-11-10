package io.github.thewisekd.phopencode.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.navigation.FilesRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileTransfers
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A file upload, end to end: a document picked on the phone goes through the transfer screen and
 * the app's transfer queue into the project folder over SFTP; the file tree then lists it, badged
 * untracked, and reading it back gives the same bytes.
 */
@RunWith(AndroidJUnit4::class)
class UploadEndToEndTest {
    private lateinit var app: E2eApp
    private val name = "e2e-upload-${UUID.randomUUID()}.bin"

    @Before
    fun setUp() = runBlocking {
        app = E2eApp(E2eEnvironment.fromArguments())
        app.setUp()
    }

    @After
    fun tearDown() = runBlocking {
        if (::app.isInitialized) {
            app.env.deleteFromProject(name)
            app.tearDown()
        }
    }

    @Test
    fun uploadsAFileIntoTheProjectFolderByteForByte() = runBlocking<Unit> {
        app.connect()
        val directory = app.env.projectDir
        // Every byte value, then enough random data to take several SFTP packets.
        val content = ByteArray(256) { it.toByte() } + Random(SEED).nextBytes(CONTENT_SIZE)

        val job = app.upload(name, content)
        assertEquals(job.toString(), TransferStatus.Done, job.status)
        assertEquals(content.size.toLong(), job.bytesDone)

        val tree = app.open<TreePresenter>(FilesRoute.Tree(directory)).presenter
        val listed = E2eApp.await(tree.state, "the file in the tree") { state ->
            !state.loading && state.entries.any { it.entry.name == name }
        }
        assertNull(listed.error)
        val row = listed.entries.single { it.entry.name == name }
        assertEquals(FileEntryType.File, row.entry.type)
        assertEquals(content.size.toLong(), row.entry.sizeBytes)
        assertEquals(GitBadge.Untracked, row.badge)

        // Read back by the path the tree shows, as a download from the tree does.
        val sink = ByteArrayOutputStream()
        app.koin.get<FileTransfers>().download(directory, row.entry.path, sink) {}
        assertArrayEquals(content, sink.toByteArray())
    }

    private companion object {
        const val SEED = 0x12e7
        const val CONTENT_SIZE = 256 * 1024 + 3
    }
}
