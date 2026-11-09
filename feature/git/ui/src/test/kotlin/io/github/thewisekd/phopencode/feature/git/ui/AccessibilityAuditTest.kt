package io.github.thewisekd.phopencode.feature.git.ui

import androidx.annotation.StringRes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.git.ui.branch.BranchesScreen
import io.github.thewisekd.phopencode.feature.git.ui.branch.branchesRoute
import io.github.thewisekd.phopencode.feature.git.ui.branch.loadedBranches
import io.github.thewisekd.phopencode.feature.git.ui.commit.CommitScreen
import io.github.thewisekd.phopencode.feature.git.ui.diff.DiffScreen
import io.github.thewisekd.phopencode.feature.git.ui.diff.WORKING_ROUTE
import io.github.thewisekd.phopencode.feature.git.ui.diff.fileDiff
import io.github.thewisekd.phopencode.feature.git.ui.diff.loaded
import io.github.thewisekd.phopencode.feature.git.ui.diff.targetOf
import io.github.thewisekd.phopencode.feature.git.ui.log.CommitDetailScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.LogScreen
import io.github.thewisekd.phopencode.feature.git.ui.log.detailRoute
import io.github.thewisekd.phopencode.feature.git.ui.log.loadedDetail
import io.github.thewisekd.phopencode.feature.git.ui.log.loadedLog
import io.github.thewisekd.phopencode.feature.git.ui.log.logRoute
import io.github.thewisekd.phopencode.feature.git.ui.remote.RemoteScreen
import io.github.thewisekd.phopencode.feature.git.ui.remote.twoRemotes
import io.github.thewisekd.phopencode.feature.git.ui.status.StatusScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.thewisekd.phopencode.feature.git.ui.commit.loaded as loadedCommit
import io.github.thewisekd.phopencode.feature.git.ui.commit.route as commitRoute
import io.github.thewisekd.phopencode.feature.git.ui.remote.route as remoteRoute
import io.github.thewisekd.phopencode.feature.git.ui.status.clean as cleanStatus
import io.github.thewisekd.phopencode.feature.git.ui.status.loaded as loadedStatus
import io.github.thewisekd.phopencode.feature.git.ui.status.route as statusRoute

/**
 * Every git screen, loaded, empty, failed and loading, at the largest font: every control is named and
 * big enough to tap, and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = GitFailure.Unreachable

    private fun audit(content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible()
    }

    /** Audits [check]'s screen, which shows a failure, and that the failure, [message], is read out. */
    private fun failed(@StringRes message: Int, check: () -> Unit) {
        check()
        compose.assertErrorAnnounced(message)
    }

    private fun status(state: StatusState) = audit { StatusScreen(statusRoute, state, {}) }

    @Test
    fun `the status`() = status(loadedStatus)

    @Test
    fun `a clean status`() = status(cleanStatus)

    @Test
    fun `the status while loading`() = status(StatusState(directory = loadedStatus.directory))

    @Test
    fun `the status failed`() = failed(R.string.git_status_error_unreachable) {
        status(StatusState(directory = loadedStatus.directory, loading = false, error = failure))
    }

    private fun commit(state: CommitState) = audit { CommitScreen(commitRoute, state, {}) }

    @Test
    fun `a commit`() = commit(loadedCommit)

    @Test
    fun `nothing to commit`() = commit(CommitState(directory = loadedCommit.directory, loading = false))

    @Test
    fun `a commit failed`() = failed(R.string.git_commit_error_unreachable) {
        commit(loadedCommit.copy(error = CommitError.Failed(CommitOp.Commit, failure)))
    }

    private fun log(state: LogState) = audit { LogScreen(logRoute, state, {}) }

    @Test
    fun `the log`() = log(loadedLog)

    @Test
    fun `an empty log`() = log(loadedLog.copy(commits = emptyList(), endReached = true))

    @Test
    fun `the log failed`() = failed(R.string.git_log_error_unreachable) {
        log(loadedLog.copy(commits = emptyList(), error = failure))
    }

    @Test
    fun `a commit's detail`() = audit { CommitDetailScreen(detailRoute, loadedDetail, {}) }

    @Test
    fun `a commit's detail failed`() = failed(R.string.git_log_error_unreachable) {
        audit {
            CommitDetailScreen(detailRoute, CommitDetailState(commit = loadedDetail.commit, loading = false, error = failure), {})
        }
    }

    private fun branches(state: BranchesState) = audit { BranchesScreen(branchesRoute, state, {}) }

    @Test
    fun `the branches`() = branches(loadedBranches)

    @Test
    fun `no branches`() = branches(loadedBranches.copy(local = emptyList(), remote = emptyList()))

    @Test
    fun `the branches failed`() = failed(R.string.git_branch_error_unreachable) {
        branches(loadedBranches.copy(local = emptyList(), remote = emptyList(), error = failure))
    }

    private fun diff(state: DiffState) = audit { DiffScreen(WORKING_ROUTE, state, {}) }

    @Test
    fun `a diff`() = diff(loaded(fileDiff()))

    @Test
    fun `a binary diff`() = diff(loaded(fileDiff(content = DiffContent.Binary)))

    @Test
    fun `a diff while loading`() = diff(DiffState(target = targetOf(WORKING_ROUTE)))

    @Test
    fun `a diff failed`() = failed(R.string.git_diff_reason_unreachable) {
        diff(DiffState(target = targetOf(WORKING_ROUTE), loading = false, error = failure))
    }

    private fun remote(state: RemoteState) = audit { RemoteScreen(remoteRoute, state, {}) }

    @Test
    fun `the remotes`() = remote(twoRemotes)

    @Test
    fun `no remotes`() = remote(twoRemotes.copy(remotes = emptyList(), selected = null))

    @Test
    fun `the remotes failed`() = failed(R.string.git_remote_error_unreachable) {
        remote(twoRemotes.copy(error = failure))
    }
}
