package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the remote screen in one language. A format takes a branch, a remote or a count. */
internal data class ExpectedRemoteStrings(
    val title: String,
    val refresh: String,
    val loading: String,
    val detached: (String) -> String,
    val tracking: (String) -> String,
    val ahead: (Int) -> String,
    val behind: (Int) -> String,
    val upToDate: String,
    val noUpstream: String,
    /** The upstream the branch tracked, deleted on the remote. */
    val upstreamGone: (String) -> String,
    val countsUnknown: String,
    val remote: (String) -> String,
    val chooseRemote: String,
    val noRemotesTitle: String,
    val fetch: String,
    val pull: String,
    val pullHint: String,
    val push: String,
    val running: Map<RemoteOp, String>,
    val cancel: String,
    val confirmTitle: (String) -> String,
    /** Commits, branch, remote. */
    val confirmMessage: (Int, String, String) -> String,
    /** Branch, remote; for a push whose commit count is not known. */
    val confirmMessageUnknown: (String, String) -> String,
    /** Branch, remote. */
    val confirmSetUpstream: (String, String) -> String,
    val confirmNoForce: String,
    val succeeded: Map<RemoteOp, String>,
    val upToDateResult: Map<RemoteOp, String>,
    val cancelled: Map<RemoteOp, String>,
    val failed: Map<RemoteOp, String>,
    val dismiss: String,
    /** Every failure but the fallback ones has its own explanation. */
    val explanations: Map<GitFailure, String>,
    val readFallback: String,
    val actionFallback: String,
    val retry: String,
)

/** Failures with their own words in each language. */
internal val EXPLAINED = listOf(
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    GitFailure.Conflict,
    GitFailure.NotConnected,
    GitFailure.Unreachable,
    GitFailure.TimedOut,
    GitFailure.Unauthorized,
    GitFailure.OutputTooLarge,
    GitFailure.NotRepository,
    GitFailure.GitMissing,
    GitFailure.Rejected,
    GitFailure.NotFound,
)

internal val FALLBACK = listOf(GitFailure.GitError(exitCode = 1), GitFailure.GitError(exitCode = null), GitFailure.Unknown)

/** What the remote screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class RemoteLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedRemoteStrings

    private fun assertInList(text: String, substring: Boolean = false) {
        val matcher = hasText(text, substring = substring)
        compose.scrollRemoteTo(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }

    @Test
    fun `the top bar and the actions are named`() {
        compose.setRemoteScreen(loaded)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        for ((tag, text) in listOf(RemoteTags.FETCH to strings.fetch, RemoteTags.PULL to strings.pull, RemoteTags.PUSH to strings.push)) {
            val action = hasTestTag(tag) and hasText(text)
            compose.scrollRemoteTo(action)
            compose.onNode(action).assertIsDisplayed()
        }
        assertInList(strings.pullHint)
    }

    @Test
    fun `the branch line counts commits ahead and behind`() {
        compose.setRemoteScreen(loaded)

        for (part in listOf(strings.tracking("origin/özellik"), strings.ahead(3), strings.behind(1))) {
            compose.onNode(hasTestTag(RemoteTags.UPSTREAM) and hasText(part, substring = true)).assertIsDisplayed()
        }
    }

    @Test
    fun `an even branch is up to date`() {
        compose.setRemoteScreen(loaded.copy(branch = branch.copy(ahead = 0, behind = 0)))
        compose.onNode(hasTestTag(RemoteTags.UPSTREAM) and hasText(strings.upToDate, substring = true)).assertIsDisplayed()
    }

    @Test
    fun `one commit ahead is worded for one`() {
        compose.setRemoteScreen(loaded.copy(branch = branch.copy(ahead = 1, behind = 0)))
        compose.onNode(hasTestTag(RemoteTags.UPSTREAM) and hasText(strings.ahead(1), substring = true)).assertIsDisplayed()
    }

    @Test
    fun `a gone upstream is named as gone, not as up to date or as none`() {
        compose.setRemoteScreen(loaded.copy(branch = branch.copy(ahead = 0, behind = 0, upstreamGone = true)))
        compose.onNode(hasTestTag(RemoteTags.UPSTREAM_GONE) and hasText(strings.upstreamGone("origin/özellik")))
            .assertIsDisplayed()
        compose.onNode(hasText(strings.upToDate, substring = true)).assertDoesNotExist()
        compose.onNode(hasText(strings.noUpstream, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `uncounted ahead and behind are not called up to date`() {
        compose.setRemoteScreen(loaded.copy(branch = branch.copy(ahead = 0, behind = 0, aheadBehindKnown = false)))
        for (part in listOf(strings.tracking("origin/özellik"), strings.countsUnknown)) {
            compose.onNode(hasTestTag(RemoteTags.UPSTREAM) and hasText(part, substring = true)).assertIsDisplayed()
        }
        compose.onNode(hasText(strings.upToDate, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a branch without an upstream says so`() {
        compose.setRemoteScreen(loaded.copy(branch = GitBranchStatus(head = "main", commit = HASH)))
        compose.onNodeWithText(strings.noUpstream).assertIsDisplayed()
    }

    @Test
    fun `a detached head is worded`() {
        compose.setRemoteScreen(loaded.copy(branch = GitBranchStatus(head = null, commit = HASH)))
        compose.onNodeWithText(strings.detached("0123456")).assertIsDisplayed()
    }

    @Test
    fun `the remote is named`() {
        compose.setRemoteScreen(loaded)
        assertInList(strings.remote("origin"))
    }

    @Test
    fun `several remotes offer a choice`() {
        compose.setRemoteScreen(twoRemotes)
        assertInList(strings.chooseRemote)
        assertInList("upstream")
    }

    @Test
    fun `no remotes is worded`() {
        compose.setRemoteScreen(loaded.copy(remotes = emptyList(), selected = null))
        assertInList(strings.noRemotesTitle)
    }

    @Test
    fun `the first load is worded`() {
        compose.setRemoteScreen(RemoteState(directory = ROOT))
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `the push confirmation says which branch goes where and how many commits`() {
        val sent = mutableListOf<RemoteIntent>()
        compose.setRemoteScreen(loaded.copy(confirmPush = plan)) { sent += it }

        compose.onNode(inDialog(strings.confirmTitle("origin"))).assertIsDisplayed()
        compose.onNode(inDialog(strings.confirmMessage(3, "özellik", "origin"), substring = true)).assertIsDisplayed()
        compose.onNode(inDialog(strings.confirmNoForce, substring = true)).assertIsDisplayed()
        compose.onNode(hasClickAction() and inDialog(strings.cancel)).performClick()
        compose.onNode(hasClickAction() and inDialog(strings.push)).performClick()
        assertEquals(listOf(RemoteIntent.DismissPush, RemoteIntent.ConfirmPush), sent)
    }

    @Test
    fun `the push confirmation words one commit, an unknown count and a new upstream`() {
        val states = RemoteStates(compose)

        states.show(loaded.copy(confirmPush = plan.copy(ahead = 1)))
        compose.onNode(inDialog(strings.confirmMessage(1, "özellik", "origin"), substring = true)).assertIsDisplayed()

        states.show(loaded.copy(confirmPush = plan.copy(ahead = 0, setUpstream = true)))
        compose.onNode(inDialog(strings.confirmMessageUnknown("özellik", "origin"), substring = true)).assertIsDisplayed()
        compose.onNode(inDialog(strings.confirmSetUpstream("özellik", "origin"), substring = true)).assertIsDisplayed()
    }

    @Test
    fun `each running operation is worded with a cancel button`() {
        val states = RemoteStates(compose)
        RemoteOp.entries.forEach { op ->
            states.show(loaded.copy(running = op))
            compose.onNode(hasTestTag(RemoteTags.RUNNING) and hasContentDescription(strings.running.getValue(op)))
                .assertIsDisplayed()
            compose.onNode(hasTestTag(RemoteTags.CANCEL) and hasText(strings.cancel)).assertIsDisplayed()
        }
    }

    @Test
    fun `each result is worded`() {
        val states = RemoteStates(compose)
        RemoteOp.entries.forEach { op ->
            listOf(
                RemoteResult.Succeeded(op) to strings.succeeded.getValue(op),
                RemoteResult.Succeeded(op, upToDate = true) to strings.upToDateResult.getValue(op),
                RemoteResult.Cancelled(op) to strings.cancelled.getValue(op),
                RemoteResult.Failed(op, GitFailure.Unknown) to strings.failed.getValue(op),
            ).forEach { (result, text) ->
                states.show(loaded.copy(result = result))
                assertResult(text)
            }
        }
        compose.onNodeWithContentDescription(strings.dismiss).assertIsDisplayed()
    }

    @Test
    fun `the result texts differ`() {
        val texts = RemoteOp.entries.flatMap {
            listOf(strings.succeeded, strings.upToDateResult, strings.cancelled, strings.failed).map { map -> map.getValue(it) }
        }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun `every failure is explained in plain words`() {
        assertEquals(EXPLAINED.toSet(), strings.explanations.keys)
        val texts = strings.explanations.values + strings.readFallback + strings.actionFallback
        assertEquals(texts.size, texts.toSet().size, "every explanation must have its own text")

        val states = RemoteStates(compose)
        EXPLAINED.forEach { failure ->
            states.show(loaded.copy(result = RemoteResult.Failed(RemoteOp.Push, failure)))
            assertResult(strings.failed.getValue(RemoteOp.Push))
            assertResult(strings.explanations.getValue(failure))
        }
        FALLBACK.forEach { failure ->
            states.show(loaded.copy(result = RemoteResult.Failed(RemoteOp.Fetch, failure)))
            assertResult(strings.actionFallback)
        }
    }

    @Test
    fun `a failed read is explained`() {
        val states = RemoteStates(compose)
        listOf(GitFailure.NotRepository, GitFailure.TimedOut, GitFailure.NotConnected).forEach { failure ->
            states.show(RemoteState(directory = ROOT, loading = false, error = failure))
            compose.onNodeWithTag(RemoteTags.ERROR).assertIsDisplayed()
            compose.onAllNodesWithText(strings.explanations.getValue(failure)).assertCountEquals(1)
        }
        FALLBACK.forEach { failure ->
            states.show(RemoteState(directory = ROOT, loading = false, error = failure))
            compose.onAllNodesWithText(strings.readFallback).assertCountEquals(1)
        }
        states.show(RemoteState(directory = ROOT, loading = false, error = GitFailure.Unreachable))
        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    private fun assertResult(text: String) {
        val matcher = hasTestTag(RemoteTags.RESULT) and hasText(text, substring = true)
        compose.scrollRemoteTo(matcher)
        compose.onNode(matcher).assertIsDisplayed()
    }
}
