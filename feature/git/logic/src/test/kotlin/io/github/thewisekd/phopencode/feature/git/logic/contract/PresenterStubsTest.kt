package io.github.thewisekd.phopencode.feature.git.logic.contract

import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.branch.DefaultBranchesPresenter
import io.github.thewisekd.phopencode.feature.git.logic.commit.DefaultCommitPresenter
import io.github.thewisekd.phopencode.feature.git.logic.diff.DefaultDiffPresenter
import io.github.thewisekd.phopencode.feature.git.logic.log.DefaultCommitDetailPresenter
import io.github.thewisekd.phopencode.feature.git.logic.log.DefaultLogPresenter
import io.github.thewisekd.phopencode.feature.git.logic.remote.DefaultRemotePresenter
import io.github.thewisekd.phopencode.feature.git.logic.status.DefaultStatusPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/**
 * Every presenter is built with the constructor the contract fixes, as `GitModule` builds it, and
 * shows its screen's initial state before anything has run. The logic tasks fill the presenters in;
 * these hold for the filled-in ones too. The connection fails every call: a presenter may not run a
 * command before its scope runs.
 */
class PresenterStubsTest {
    private val git = object : GitConnection {
        override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = error("Not called before the scope runs")
    }

    @Test
    fun `status starts loading the repository`() = runTest {
        val presenter: StatusPresenter = DefaultStatusPresenter(directory = ROOT, git = git, scope = backgroundScope)

        assertEquals(StatusState(directory = ROOT), presenter.state.value)
    }

    @Test
    fun `a diff starts loading its target`() = runTest {
        val target = DiffTarget.Staged("src/main kodu.kt")
        val presenter: DiffPresenter = DefaultDiffPresenter(directory = ROOT, target = target, git = git, scope = backgroundScope)

        assertEquals(DiffState(target = target), presenter.state.value)
        assertTrue(DefaultDiffPresenter.DEFAULT_MAX_BYTES > 0)
    }

    @Test
    fun `the log starts loading its first page`() = runTest {
        val presenter: LogPresenter = DefaultLogPresenter(directory = ROOT, git = git, scope = backgroundScope)

        assertEquals(LogState(directory = ROOT), presenter.state.value)
        assertTrue(DefaultLogPresenter.DEFAULT_PAGE_SIZE > 0)
    }

    @Test
    fun `commit details start loading the commit`() = runTest {
        val presenter: CommitDetailPresenter =
            DefaultCommitDetailPresenter(directory = ROOT, commit = HASH, git = git, scope = backgroundScope)

        assertEquals(CommitDetailState(commit = HASH), presenter.state.value)
    }

    @Test
    fun `the commit screen starts loading with an empty message`() = runTest {
        val presenter: CommitPresenter = DefaultCommitPresenter(directory = ROOT, git = git, scope = backgroundScope)

        assertEquals(CommitState(directory = ROOT), presenter.state.value)
    }

    @Test
    fun `branches start loading`() = runTest {
        val presenter: BranchesPresenter = DefaultBranchesPresenter(directory = ROOT, git = git, scope = backgroundScope)

        assertEquals(BranchesState(directory = ROOT), presenter.state.value)
    }

    @Test
    fun `remote operations start loading with nothing running`() = runTest {
        val presenter: RemotePresenter = DefaultRemotePresenter(directory = ROOT, git = git, scope = backgroundScope)

        assertEquals(RemoteState(directory = ROOT), presenter.state.value)
    }
}
