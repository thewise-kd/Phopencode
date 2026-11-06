package io.github.thewisekd.phopencode.feature.git.ui.remote

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the remote screen sends and which controls it enables; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class RemoteScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<RemoteIntent>()

    private fun show(state: RemoteState) = compose.setRemoteScreen(state) { sent += it }

    private val tagMentioningForce = SemanticsMatcher("test tag mentions force") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.contains("force", ignoreCase = true) == true
    }

    private fun clickAction(tag: String) {
        compose.scrollRemoteTo(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loaded)
        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `the root keeps the route's placeholder tag while loading`() {
        show(RemoteState(directory = ROOT))
        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        compose.onNodeWithTag(RemoteTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun `fetch sends fetch`() {
        show(loaded)
        clickAction(RemoteTags.FETCH)
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Fetch), sent)
    }

    @Test
    fun `pull sends pull`() {
        show(loaded)
        clickAction(RemoteTags.PULL)
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Pull), sent)
    }

    @Test
    fun `push only asks for the confirmation`() {
        show(loaded)
        clickAction(RemoteTags.PUSH)
        assertEquals(listOf<RemoteIntent>(RemoteIntent.RequestPush), sent)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun `the push confirmation names branch, remote and commits, and confirming pushes`() {
        show(loaded.copy(confirmPush = plan))

        compose.onNode(inDialog("Push 3 commits from özellik to origin?", substring = true)).assertIsDisplayed()
        compose.onNode(hasClickAction() and inDialog("Push")).performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.ConfirmPush), sent)
    }

    @Test
    fun `cancelling the push confirmation dismisses it`() {
        show(loaded.copy(confirmPush = plan))

        compose.onNode(hasClickAction() and inDialog("Cancel")).performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.DismissPush), sent)
    }

    @Test
    fun `no control anywhere offers to force`() {
        val states = RemoteStates(compose) { sent += it }
        listOf(
            loaded,
            twoRemotes,
            loaded.copy(confirmPush = plan),
            loaded.copy(confirmPush = plan.copy(setUpstream = true)),
            loaded.copy(running = RemoteOp.Push),
            loaded.copy(result = RemoteResult.Failed(RemoteOp.Push, GitFailure.NonFastForward)),
            loaded.copy(result = RemoteResult.Failed(RemoteOp.Pull, GitFailure.Diverged)),
        ).forEach { state ->
            states.show(state)
            compose.onAllNodes(hasClickAction() and hasText("force", substring = true, ignoreCase = true))
                .assertCountEquals(0)
            compose.onAllNodes(tagMentioningForce).assertCountEquals(0)
            compose.onAllNodes(hasClickAction() and hasText("--", substring = true)).assertCountEquals(0)
        }
        // The dialog has exactly the confirm and cancel buttons.
        states.show(loaded.copy(confirmPush = plan))
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isDialog())).assertCountEquals(2)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a running operation shows its progress and can be cancelled`() {
        show(loaded.copy(running = RemoteOp.Pull))

        compose.onNodeWithTag(RemoteTags.RUNNING).assertIsDisplayed()
        val progress = compose.onNodeWithTag(RemoteTags.RUNNING).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.ProgressBarRangeInfo in progress)
        compose.onNodeWithTag(RemoteTags.CANCEL).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Cancel), sent)
    }

    @Test
    fun `nothing else starts while an operation runs`() {
        show(loaded.copy(running = RemoteOp.Fetch))

        for (tag in listOf(RemoteTags.FETCH, RemoteTags.PULL, RemoteTags.PUSH)) {
            compose.scrollRemoteTo(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag(RemoteTags.REFRESH).assertIsNotEnabled()
    }

    /** A short screen, so that the actions at the bottom push the branch out of view. */
    @Test
    @Config(qualifiers = "en-w320dp-h320dp")
    fun `starting an operation while scrolled down keeps cancel in view`() {
        val states = RemoteStates(compose) { sent += it }
        states.show(twoRemotes)
        compose.scrollRemoteTo(hasTestTag(RemoteTags.PUSH))
        compose.onNodeWithTag(RemoteTags.BRANCH).assertIsNotDisplayed()

        states.show(twoRemotes.copy(running = RemoteOp.Push))
        compose.onNodeWithTag(RemoteTags.RUNNING).assertIsDisplayed()
        compose.onNodeWithTag(RemoteTags.CANCEL).assertIsDisplayed().performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Cancel), sent)
    }

    @Test
    @Config(qualifiers = "en-w320dp-h320dp")
    fun `a result that appears while scrolled down is brought into view`() {
        val states = RemoteStates(compose) { sent += it }
        states.show(twoRemotes)
        compose.scrollRemoteTo(hasTestTag(RemoteTags.PUSH))
        compose.onNodeWithTag(RemoteTags.BRANCH).assertIsNotDisplayed()

        states.show(twoRemotes.copy(result = RemoteResult.Failed(RemoteOp.Push, GitFailure.NonFastForward)))
        compose.onNodeWithTag(RemoteTags.RESULT).assertIsDisplayed()
    }

    @Test
    fun `no cancel shows when nothing runs`() {
        show(loaded)
        compose.onAllNodesWithTag(RemoteTags.CANCEL).assertCountEquals(0)
        compose.onAllNodesWithTag(RemoteTags.RUNNING).assertCountEquals(0)
    }

    @Test
    fun `the actions are touch targets of at least 48 dp`() {
        show(loaded)
        for (tag in listOf(RemoteTags.FETCH, RemoteTags.PULL, RemoteTags.PUSH)) {
            compose.scrollRemoteTo(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsEnabled().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `without a selected remote nothing can start`() {
        show(loaded.copy(remotes = emptyList(), selected = null))

        compose.scrollRemoteTo(hasTestTag(RemoteTags.NO_REMOTES))
        compose.onNodeWithTag(RemoteTags.NO_REMOTES).assertIsDisplayed()
        for (tag in listOf(RemoteTags.FETCH, RemoteTags.PULL, RemoteTags.PUSH)) {
            compose.scrollRemoteTo(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
    }

    @Test
    fun `a remote can be chosen when there are several`() {
        show(twoRemotes)

        compose.scrollRemoteTo(hasTestTag(RemoteTags.remoteOption("origin")))
        compose.onNodeWithTag(RemoteTags.remoteOption("origin")).assertIsSelected()
        compose.scrollRemoteTo(hasTestTag(RemoteTags.remoteOption("upstream")))
        compose.onNodeWithTag(RemoteTags.remoteOption("upstream"))
            .assertIsNotSelected()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.SelectRemote("upstream")), sent)
    }

    @Test
    fun `the remote cannot be changed while an operation runs`() {
        show(twoRemotes.copy(running = RemoteOp.Fetch))

        compose.scrollRemoteTo(hasTestTag(RemoteTags.remoteOption("upstream")))
        compose.onNodeWithTag(RemoteTags.remoteOption("upstream")).assertIsNotEnabled()
    }

    @Test
    fun `one remote is named without a choice`() {
        show(loaded)

        compose.scrollRemoteTo(hasTestTag(RemoteTags.REMOTE))
        compose.onNode(hasTestTag(RemoteTags.REMOTE) and hasText("origin", substring = true)).assertIsDisplayed()
        compose.onAllNodesWithTag(RemoteTags.remoteOption("origin")).assertCountEquals(0)
    }

    @Test
    fun `the branch, its upstream and the counts are shown`() {
        show(loaded)

        compose.onNode(hasTestTag(RemoteTags.BRANCH) and hasText("özellik", substring = true)).assertIsDisplayed()
        compose.onNode(hasTestTag(RemoteTags.UPSTREAM) and hasText("origin/özellik", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a result can be dismissed`() {
        show(loaded.copy(result = RemoteResult.Succeeded(RemoteOp.Fetch)))

        compose.scrollRemoteTo(hasTestTag(RemoteTags.RESULT))
        compose.onNodeWithTag(RemoteTags.RESULT).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.DismissResult), sent)
    }

    @Test
    fun `back closes the screen`() {
        show(loaded)
        compose.onNodeWithContentDescription("Back", substring = true, ignoreCase = true).performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Close), sent)
    }

    @Test
    fun `refresh reads again`() {
        show(loaded)
        compose.onNodeWithTag(RemoteTags.REFRESH).assertIsEnabled().performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Refresh), sent)
    }

    @Test
    fun `refresh retries after a failed read`() {
        show(RemoteState(directory = ROOT, loading = false, error = GitFailure.Unreachable))
        compose.onNodeWithTag(RemoteTags.REFRESH).performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf<RemoteIntent>(RemoteIntent.Retry, RemoteIntent.Retry), sent)
    }

    @Test
    fun `a failed read hides the actions`() {
        show(RemoteState(directory = ROOT, loading = false, error = GitFailure.TimedOut))

        compose.onNodeWithTag(RemoteTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithTag(RemoteTags.FETCH).assertCountEquals(0)
        compose.onAllNodesWithTag(RemoteTags.PUSH).assertCountEquals(0)
    }

    @Test
    fun `no retry is offered when the connection is gone`() {
        show(RemoteState(directory = ROOT, loading = false, error = GitFailure.NotConnected))

        compose.onNodeWithTag(RemoteTags.ERROR).assertIsDisplayed()
        compose.onAllNodes(hasText("Try again")).assertCountEquals(0)
    }

    @Test
    fun `refresh waits while loading`() {
        show(RemoteState(directory = ROOT))
        compose.onNodeWithTag(RemoteTags.REFRESH).assertIsNotEnabled()
    }
}
