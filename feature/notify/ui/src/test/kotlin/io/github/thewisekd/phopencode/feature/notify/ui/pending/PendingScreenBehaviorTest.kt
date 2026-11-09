package io.github.thewisekd.phopencode.feature.notify.ui.pending

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.PendingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the pending list sends and keeps hidden, in English; the words themselves are in the language tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PendingScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = ENGLISH_PENDING_STRINGS

    @Test
    fun `the root keeps its route's tag`() {
        compose.setPending(listed)
        compose.onNodeWithTag("placeholder:${NotifyRoute.Pending}").assertIsDisplayed()
    }

    @Test
    fun `tapping an item opens it, a permission of a subagent too`() {
        val log = compose.setPending(listed)

        compose.onNodeWithTag(pendingItemTag(question)).performClick()
        compose.onNodeWithTag(PENDING_LIST_TAG).performScrollToNode(hasText(permission.sessionTitle!!))
        compose.onNodeWithTag(pendingItemTag(permission)).performClick()

        compose.runOnIdle {
            assertEquals(listOf<PendingIntent>(PendingIntent.Open(question), PendingIntent.Open(permission)), log.intents)
        }
    }

    @Test
    fun `items are at least a touch target tall`() {
        compose.setPending(listed)
        compose.onNodeWithTag(pendingItemTag(otherForm)).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `an item shows its kind, its session and the form's title or the permission's action only`() {
        compose.setPending(listed)

        compose.onNodeWithText(question.form.title!!).assertIsDisplayed()
        compose.onNodeWithText(question.sessionTitle!!).assertIsDisplayed()
        compose.onNodeWithTag(PENDING_LIST_TAG).performScrollToNode(hasText(permission.request.action))
        compose.onNodeWithText(permission.request.action).assertIsDisplayed()
        // Fields, resources and messages are the server's text and may hold a path or a command.
        listOf("secret-field-title", "secret-resource", "secret-permission-message").forEach { secret ->
            compose.onAllNodesWithText(secret, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun `the refresh button reads the list again, and waits while it is read`() {
        val log = compose.setPending(listed)

        compose.onNodeWithContentDescription(strings.refresh).assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(listOf<PendingIntent>(PendingIntent.Refresh), log.intents) }
    }

    @Test
    fun `the refresh button waits while loading`() {
        compose.setPending(PendingState())
        compose.onNodeWithContentDescription(strings.refresh).assertIsNotEnabled()
    }

    @Test
    fun `the refresh button waits while refreshing`() {
        compose.setPending(listed.copy(refreshing = true))
        compose.onNodeWithContentDescription(strings.refresh).assertIsNotEnabled()
    }

    @Test
    fun `back closes the list`() {
        val log = compose.setPending(listed)

        compose.onNodeWithContentDescription("Go back").performClick()

        compose.runOnIdle { assertEquals(listOf<PendingIntent>(PendingIntent.Close), log.intents) }
    }

    @Test
    fun `the bar's buttons take a touch target each`() {
        compose.setPending(listed)

        listOf("Go back", strings.refresh).forEach { label ->
            // Material's icon buttons draw 40 dp and widen their touch area to 48 dp around it.
            val touch = compose.onNodeWithContentDescription(label).fetchSemanticsNode().touchBoundsInRoot
            val target = with(compose.density) { 48.dp.toPx() } - 0.5f
            assertTrue(touch.width >= target && touch.height >= target, "$label touches $touch")
        }
    }

    @Test
    fun `an error with nothing listed retries in place`() {
        val log = compose.setPending(PendingState(loading = false, error = NotifyFailure.Unreachable))

        compose.onNodeWithText(strings.retry).performClick()

        compose.runOnIdle { assertEquals(listOf<PendingIntent>(PendingIntent.Refresh), log.intents) }
        compose.onNodeWithText(strings.empty).assertDoesNotExist()
    }

    @Test
    fun `without a connection the list goes to connect`() {
        val log = compose.setPending(PendingState(loading = false, error = NotifyFailure.NotConnected))

        compose.onNodeWithText(strings.connect).performClick()

        compose.runOnIdle { assertEquals(listOf<PendingIntent>(PendingIntent.Connect), log.intents) }
    }

    @Test
    fun `an error above kept items can be retried or dismissed, and the items stay`() {
        val log = compose.setPending(listed.copy(error = NotifyFailure.ServerFailed))

        compose.onNodeWithText(strings.errors.getValue(NotifyFailure.ServerFailed)).assertIsDisplayed()
        compose.onNodeWithText(question.sessionTitle!!).assertIsDisplayed()
        compose.onNodeWithText(strings.retry).performClick()
        compose.onNodeWithContentDescription("Dismiss error").performClick()

        compose.runOnIdle {
            assertEquals(listOf(PendingIntent.Refresh, PendingIntent.DismissError), log.intents)
        }
    }

    @Test
    fun `a lost connection above kept items offers to connect`() {
        val log = compose.setPending(listed.copy(error = NotifyFailure.NotConnected))

        compose.onNodeWithText(strings.connect).performClick()

        compose.runOnIdle { assertEquals(listOf<PendingIntent>(PendingIntent.Connect), log.intents) }
    }

    @Test
    fun `the empty state shows an icon and one line`() {
        compose.setPending(PendingState(loading = false))

        compose.onNodeWithText(strings.empty).assertIsDisplayed()
        compose.onNodeWithTag(PENDING_EMPTY_TAG).assertIsDisplayed()
        // core/ui's EmptyState marks its icon circle with this tag.
        compose.onNodeWithTag("ui-common:empty-state-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.loading).assertDoesNotExist()
    }
}
