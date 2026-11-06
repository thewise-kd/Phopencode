package io.github.thewisekd.phopencode.feature.git.logic.log

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CommitDetailPresenterTest {
    private val exec = FakeRemoteExec()
    private val git = ExecGitConnection(exec)
    private val commit = hash(7)

    private val header = LogCommands.commit(DIR, commit).commandLine
    private val numStat = LogCommands.numStat(DIR, commit).commandLine
    private val nameStatus = LogCommands.nameStatus(DIR, commit).commandLine

    private fun TestScope.presenter(hash: String = commit) = DefaultCommitDetailPresenter(DIR, hash, git, backgroundScope)

    private fun scriptFiles() {
        exec.onOutput(numStat, "2\t1\tsrc/ana kod.kt\u0000-\t-\tlogo.png\u00000\t0\t\u0000eski.txt\u0000yeni.txt\u00000\t4\tsil.txt\u0000")
        exec.onOutput(nameStatus, "M\u0000src/ana kod.kt\u0000A\u0000logo.png\u0000R100\u0000eski.txt\u0000yeni.txt\u0000D\u0000sil.txt\u0000")
    }

    @Test
    fun `a commit loads its message, people, dates, parents and files`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6)), subject = "Düzeltme: çökme", body = "Ayrıntı satırı\n\nİkinci paragraf"))
        scriptFiles()
        val presenter = presenter()
        assertTrue(presenter.state.value.loading)

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        val detail = assertNotNull(state.detail)
        assertEquals(commit, detail.summary.hash)
        assertEquals(listOf(hash(6)), detail.summary.parents)
        assertEquals("Düzeltme: çökme", detail.summary.subject)
        assertEquals("Ayrıntı satırı\n\nİkinci paragraf", detail.body)
        assertEquals("Ayşe Yılmaz", detail.summary.authorName)
        assertEquals("ayse@example.com", detail.summary.authorEmail)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000), detail.summary.authoredAt)
        assertEquals("Çağrı Öztürk", detail.committerName)
        assertEquals("cagri@example.com", detail.committerEmail)
        assertEquals(Instant.fromEpochSeconds(1_700_000_100), detail.committedAt)
        assertEquals(
            listOf(
                CommitFileChange("src/ana kod.kt", GitChangeKind.Modified, additions = 2, deletions = 1),
                CommitFileChange("logo.png", GitChangeKind.Added),
                CommitFileChange("yeni.txt", GitChangeKind.Renamed, oldPath = "eski.txt", additions = 0, deletions = 0),
                CommitFileChange("sil.txt", GitChangeKind.Deleted, additions = 0, deletions = 4),
            ),
            detail.files,
        )
        assertTrue(detail.files[1].isBinary)
    }

    @Test
    fun `a merge lists its files against the first parent`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6), hash(5)), subject = "Merge"))
        exec.onOutput(numStat, "1\t0\tb\u0000-\t-\tbin\u0000")
        val presenter = presenter()

        runCurrent()

        val detail = assertNotNull(presenter.state.value.detail)
        assertTrue(detail.summary.isMerge)
        assertEquals(
            listOf(CommitFileChange("b", GitChangeKind.Modified, additions = 1, deletions = 0), CommitFileChange("bin", GitChangeKind.Modified)),
            detail.files,
        )
        assertFalse(nameStatus in exec.commands())
    }

    @Test
    fun `a commit without changes has no files`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6))))
        exec.onOutput(numStat, "")
        exec.onOutput(nameStatus, "")
        val presenter = presenter()

        runCurrent()

        assertEquals(emptyList(), assertNotNull(presenter.state.value.detail).files)
    }

    @Test
    fun `an invalid hash is rejected without running anything`() = runTest {
        for (bad in listOf("", "HEAD", "-n1", "abcdef0123".repeat(4).uppercase(), hash(7).take(39), hash(7) + "0", "main", "${hash(7)}^", " ${hash(7)}")) {
            val presenter = presenter(bad)
            runCurrent()
            assertEquals(GitFailure.Rejected, presenter.state.value.error, bad)
            assertFalse(presenter.state.value.loading)
        }
        assertEquals(emptyList(), exec.commands())
    }

    @Test
    fun `a sha-256 hash is accepted`() = runTest {
        val long = "a".repeat(64)
        exec.onOutput(LogCommands.commit(DIR, long).commandLine, record(long))
        exec.onOutput(LogCommands.numStat(DIR, long).commandLine, "1\t0\ta\u0000")
        exec.onOutput(LogCommands.nameStatus(DIR, long).commandLine, "A\u0000a\u0000")
        val presenter = presenter(long)

        runCurrent()

        assertEquals(long, assertNotNull(presenter.state.value.detail).summary.hash)
    }

    @Test
    fun `an unknown commit is not found`() = runTest {
        exec.onOutput(header, "", exit = 128, stderr = "fatal: bad revision '${commit}^{commit}'\n")
        val presenter = presenter()

        runCurrent()

        assertEquals(GitFailure.NotFound, presenter.state.value.error)
        assertNull(presenter.state.value.detail)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a hash that peels to another commit is not found`() = runTest {
        exec.onOutput(header, record(hash(8)))
        val presenter = presenter()

        runCurrent()

        assertEquals(GitFailure.NotFound, presenter.state.value.error)
        assertFalse(numStat in exec.commands())
    }

    @Test
    fun `more than one header record is not trusted`() = runTest {
        exec.onOutput(header, record(commit) + record(hash(8)))
        val presenter = presenter()

        runCurrent()

        assertEquals(GitFailure.Unknown, presenter.state.value.error)
    }

    @Test
    fun `file lists that disagree are an error`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6))))
        exec.onOutput(numStat, "1\t0\ta\u0000")
        exec.onOutput(nameStatus, "A\u0000b\u0000")
        val presenter = presenter()

        runCurrent()

        assertEquals(GitFailure.Unknown, presenter.state.value.error)
    }

    @Test
    fun `failures of the file list map to git failures`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6))))
        exec.failNext(SshException.Timeout())
        // The first command consumes the failure: the header.
        val presenter = presenter()
        runCurrent()
        assertEquals(GitFailure.TimedOut, presenter.state.value.error)

        exec.onOutput(numStat, "", exit = 128, stderr = "fatal: not a git repository\n")
        presenter.onIntent(CommitDetailIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()
        assertEquals(GitFailure.NotRepository, presenter.state.value.error)

        scriptFiles()
        presenter.onIntent(CommitDetailIntent.Retry)
        runCurrent()
        assertNull(presenter.state.value.error)
        assertEquals(4, assertNotNull(presenter.state.value.detail).files.size)
    }

    @Test
    fun `retry while loaded does nothing`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6))))
        scriptFiles()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(CommitDetailIntent.Retry)
        runCurrent()

        assertEquals(1, exec.commands().count { it == header })
    }

    @Test
    fun `a listed file opens its diff, anything else is ignored`() = runTest {
        exec.onOutput(header, record(commit, parents = listOf(hash(6))))
        scriptFiles()
        val presenter = presenter()

        presenter.onIntent(CommitDetailIntent.OpenFile("yeni.txt"))
        runCurrent()
        presenter.onIntent(CommitDetailIntent.OpenFile("başka.txt"))
        presenter.onIntent(CommitDetailIntent.OpenFile("yeni.txt"))
        presenter.onIntent(CommitDetailIntent.Close)

        presenter.events.test {
            assertEquals(CommitDetailEvent.OpenFileDiff("yeni.txt"), awaitItem())
            assertEquals(CommitDetailEvent.Close, awaitItem())
            expectNoEvents()
        }
    }
}
