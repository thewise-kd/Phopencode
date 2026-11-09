package io.github.thewisekd.phopencode.feature.chat.ui.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TOUCH_SIZE_TOLERANCE = 0.5.dp

/** Which parts each status draws, and what its buttons send; the texts are in `StatusLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class StatusBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val parts = listOf(
        STATUS_DISCONNECTED_TAG,
        STATUS_ACTIVITY_TAG,
        STATUS_FAILED_TAG,
        STATUS_BLOCKING_TAG,
        STATUS_RECONNECT_TAG,
        STATUS_CLOSE_TAG,
    )

    /**
     * Scrolled into view first: at API 35+ the insets push the content down. The blocking status is
     * the scrolling container itself, so it has nothing to scroll in.
     */
    private fun part(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        return if (tag == STATUS_BLOCKING_TAG) node.assertIsDisplayed() else node.performScrollTo().assertIsDisplayed()
    }

    /** Exactly [expected] of the status's parts are drawn. */
    private fun assertParts(vararg expected: String) {
        compose.waitForIdle()
        parts.forEach { tag ->
            if (tag in expected) part(tag) else compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    // The disconnect banner

    @Test
    fun `a dropped connection shows a banner with Reconnect`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Disconnected))

        assertParts(STATUS_DISCONNECTED_TAG, STATUS_RECONNECT_TAG)
        part(STATUS_RECONNECT_TAG).performClick()
        assertEquals(listOf<StatusIntent>(StatusIntent.Reconnect), harness.intents)
    }

    @Test
    fun `the banner goes once the work runs again`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Disconnected))
        part(STATUS_DISCONNECTED_TAG)

        harness.state.value = StatusState(ChatStatus.Running)
        assertParts(STATUS_ACTIVITY_TAG)
    }

    @Test
    fun `the banner goes once the session is idle again`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Disconnected))
        part(STATUS_DISCONNECTED_TAG)

        harness.state.value = StatusState(ChatStatus.Idle)
        assertParts()
        compose.onNodeWithTag(SLOT_TAG).assertExists()
        compose.onNodeWithTag(BELOW_TAG).assertExists()
    }

    @Test
    fun `the Reconnect button is large enough to touch`() {
        compose.setStatus(StatusState(ChatStatus.Disconnected))

        part(STATUS_RECONNECT_TAG).assertTouchSizeIsAtLeast(48.dp)
    }

    // The compact lines

    @Test
    fun `an idle session draws nothing`() {
        compose.setStatus(StatusState(ChatStatus.Idle))

        assertParts()
    }

    @Test
    fun `running work is a still progress line`() {
        compose.setStatus(StatusState(ChatStatus.Running))

        assertParts(STATUS_ACTIVITY_TAG)
        val node = part(STATUS_ACTIVITY_TAG).fetchSemanticsNode()
        assertEquals(ProgressBarRangeInfo.Indeterminate, node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun `a retry is a progress line too`() {
        compose.setStatus(StatusState(ChatStatus.Retrying(attempt = 3, nextAttemptAt = NEXT_TRY)))

        assertParts(STATUS_ACTIVITY_TAG)
    }

    @Test
    fun `a failure shows the error and no button`() {
        compose.setStatus(StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth"))))

        assertParts(STATUS_FAILED_TAG)
    }

    @Test
    fun `running work and a failure are slim strips`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Running))
        assertSlim(STATUS_ACTIVITY_TAG)

        harness.state.value = StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth", message = "refused")))
        compose.waitForIdle()
        assertSlim(STATUS_FAILED_TAG)
    }

    @Test
    fun `a failure strip says it is an error and is announced`() {
        compose.setStatus(StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth"))))

        val config = part(STATUS_FAILED_TAG).fetchSemanticsNode().config
        assertEquals("Error", config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(LiveRegionMode.Polite, config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `the disconnected strip holds its Reconnect`() {
        compose.setStatus(StatusState(ChatStatus.Disconnected))

        val strip = part(STATUS_DISCONNECTED_TAG).fetchSemanticsNode()
        val button = part(STATUS_RECONNECT_TAG).fetchSemanticsNode()
        assertTrue(strip.boundsInRoot.contains(button.boundsInRoot.center), "Reconnect sits in the strip")
    }

    @Test
    fun `a long failure shows in full`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth", message = "refused"))))
        compose.waitForIdle()
        val short = part(STATUS_FAILED_TAG).fetchSemanticsNode().boundsInRoot.height

        harness.state.value = StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth", message = LONG_ERROR_TEXT)))
        compose.waitForIdle()

        // Thirteen lines against one: every line of the error is on screen, none is cut.
        val full = part(STATUS_FAILED_TAG).fetchSemanticsNode().boundsInRoot.height
        assertTrue(full > short * 6f, "the whole error shows: $short -> $full")
    }

    @Test
    fun `screen readers get the whole failure`() {
        compose.setStatus(StatusState(ChatStatus.Failed(StructuredError(type = "provider.auth", message = LONG_ERROR_TEXT))))

        val text = part(STATUS_FAILED_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertTrue(text.any { it.text.endsWith("SON") }, "the full text is in the semantics: $text")
    }

    private fun assertSlim(tag: String) {
        val node = part(tag).fetchSemanticsNode()
        val height = with(node.layoutInfo.density) { node.boundsInRoot.height.toDp() }
        assertTrue(height <= 56.dp, "$tag is a slim strip, was $height")
    }

    // The whole chat

    @Test
    fun `loading takes the chat with no button`() {
        compose.setStatus(StatusState(ChatStatus.Loading))

        assertParts(STATUS_BLOCKING_TAG)
    }

    @Test
    fun `no connection offers only Close, as Reconnect needs a standing connection`() {
        val harness = compose.setStatus(StatusState(ChatStatus.NotConnected))

        assertParts(STATUS_BLOCKING_TAG, STATUS_CLOSE_TAG)
        part(STATUS_CLOSE_TAG).assertTouchSizeIsAtLeast(48.dp).performClick()
        assertEquals(listOf<StatusIntent>(StatusIntent.Close), harness.intents)
    }

    @Test
    fun `the Reconnect of an unavailable session is large enough and sends Reconnect`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Unavailable(ChatFailure.Unreachable)))

        part(STATUS_RECONNECT_TAG).assertTouchSizeIsAtLeast(48.dp).performClick()
        assertEquals(listOf<StatusIntent>(StatusIntent.Reconnect), harness.intents)
    }

    @Test
    fun `every blocking status draws inside a scrolling parent`() {
        val state = mutableStateOf(StatusState())
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        StatusBanner(state.value, {}, Modifier.fillMaxWidth().testTag(SLOT_TAG))
                    }
                }
            }
        }
        val blocking = listOf(ChatStatus.Loading, ChatStatus.NotConnected, ChatStatus.NotFound, ChatStatus.OtherServer) +
            ChatFailure.entries.map { ChatStatus.Unavailable(it) }
        blocking.forEach { status ->
            state.value = StatusState(status)
            compose.waitForIdle()
            compose.onNodeWithTag(STATUS_BLOCKING_TAG).assertExists()
            if (status != ChatStatus.Loading) part(STATUS_CLOSE_TAG)
        }
    }

    @Test
    fun `a missing session offers only Close`() {
        val harness = compose.setStatus(StatusState(ChatStatus.NotFound))

        assertParts(STATUS_BLOCKING_TAG, STATUS_CLOSE_TAG)
        part(STATUS_CLOSE_TAG).performClick()
        assertEquals(listOf<StatusIntent>(StatusIntent.Close), harness.intents)
    }

    @Test
    fun `a session of another server offers only Close, as reconnecting reaches the same other server`() {
        compose.setStatus(StatusState(ChatStatus.OtherServer))

        assertParts(STATUS_BLOCKING_TAG, STATUS_CLOSE_TAG)
    }

    @Test
    fun `an unavailable session offers Reconnect only when a new attempt can help`() {
        val harness = compose.setStatus(StatusState(ChatStatus.Unavailable(ChatFailure.Unreachable)))
        val retryable = setOf(
            ChatFailure.Unreachable,
            ChatFailure.ServerFailed,
            ChatFailure.Unknown,
        )
        ChatFailure.entries.forEach { failure ->
            harness.state.value = StatusState(ChatStatus.Unavailable(failure))
            if (failure in retryable) {
                assertParts(STATUS_BLOCKING_TAG, STATUS_RECONNECT_TAG, STATUS_CLOSE_TAG)
            } else {
                assertParts(STATUS_BLOCKING_TAG, STATUS_CLOSE_TAG)
            }
        }
    }

    @Test
    fun `a blocking status fills the space the chat gives it`() {
        compose.setStatus(StatusState(ChatStatus.OtherServer))
        compose.waitForIdle()

        val slot = compose.onNodeWithTag(SLOT_TAG).fetchSemanticsNode().boundsInRoot
        val block = compose.onNodeWithTag(STATUS_BLOCKING_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(slot.height > 0f, "the slot has no height")
        assertEquals(slot, block)
    }
}

/** The node's touch bounds are at least [minimum] in both directions. */
private fun SemanticsNodeInteraction.assertTouchSizeIsAtLeast(minimum: Dp): SemanticsNodeInteraction {
    val node = fetchSemanticsNode("Failed to check the touch size.")
    val bounds: Rect = node.touchBoundsInRoot
    val (width, height) = with(node.layoutInfo.density) { bounds.width.toDp() to bounds.height.toDp() }
    if (width < minimum - TOUCH_SIZE_TOLERANCE || height < minimum - TOUCH_SIZE_TOLERANCE) {
        throw AssertionError("Touch size is $width x $height, expected at least $minimum")
    }
    return this
}
