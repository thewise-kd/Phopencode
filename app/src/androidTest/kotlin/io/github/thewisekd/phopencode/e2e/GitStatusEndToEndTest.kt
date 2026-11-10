package io.github.thewisekd.phopencode.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.feature.files.logic.contract.TransferStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusPresenter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Git status, end to end: after a file is uploaded into the project's repository, the git status
 * screen reads the repository over SSH and shows the file as an untracked change on `main`, with
 * the committed file unchanged.
 */
@RunWith(AndroidJUnit4::class)
class GitStatusEndToEndTest {
    private lateinit var app: E2eApp
    private val name = "e2e-status-${UUID.randomUUID()}.txt"

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
    fun anUploadedFileShowsAsAnUntrackedChange() = runBlocking<Unit> {
        app.connect()
        val job = app.upload(name, "Uploaded by the end-to-end test.\n".encodeToByteArray())
        assertEquals(job.toString(), TransferStatus.Done, job.status)

        val status = app.open<StatusPresenter>(GitRoute.Status(app.env.projectDir)).presenter
        val shown = E2eApp.await(status.state, "the git status") { !it.loading }

        assertNull(shown.toString(), shown.error)
        assertEquals("main", shown.branch?.head)
        assertTrue(shown.toString(), GitFileChange(name, GitChangeKind.Untracked) in shown.untracked)
        assertEquals(emptyList<GitFileChange>(), shown.staged)
        assertEquals(emptyList<GitFileChange>(), shown.unstaged)
        assertEquals(emptyList<GitFileChange>(), shown.conflicted)
    }
}
