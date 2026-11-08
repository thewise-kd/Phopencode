package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.timelineItemTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * On a window as short as a landscape phone or a chat with the keyboard open, the pending cards
 * give way to the timeline: it keeps its minimum height, the cards scroll in their own area, and
 * the composer, with the switcher in it, stays on screen. A tall window keeps the layout it always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-w320dp-h320dp")
class ChatShortWindowTest {
    @get:Rule
    val compose = createComposeRule()

    private val route = ChatRoute.Chat(sessionId = "ses_1")
    private val prompts = (1..12).map { n ->
        val id = MessageId("msg_u$n")
        TimelineItem.User(TimelineKey.message(id), id, text = "Prompt $n")
    }
    private val latest = prompts.last()
    private val permission = PermissionCardState(PermissionRequestId("per_1"), SessionId("ses_2"), fromSubagent = true, action = "bash")

    private fun formsOf(count: Int) = FormsState(
        cards = (1..count).map { n -> FormCardState(formId = FormId("frm_$n"), sessionId = SessionId("ses_1")) },
    )

    private fun setChat(forms: FormsState) {
        compose.setContent {
            PhopencodeTheme {
                ChatScreen(
                    route = route,
                    status = StatusState(ChatStatus.Idle),
                    onStatusIntent = {},
                    usage = UsageState(),
                    onUsageIntent = {},
                    timeline = TimelineState(loading = false, items = prompts),
                    onTimelineIntent = {},
                    reasoning = ReasoningState(),
                    onReasoningIntent = {},
                    tool = ToolState(),
                    onToolIntent = {},
                    composer = ComposerState(),
                    onComposerIntent = {},
                    forms = forms,
                    onFormIntent = {},
                    permissions = PermissionsState(cards = listOf(permission)),
                    onPermissionIntent = {},
                    subagents = SubagentState(),
                    onSubagentIntent = {},
                    palette = PaletteState(),
                    onPaletteIntent = {},
                    switcher = SwitcherState(),
                    onSwitcherIntent = {},
                    actions = ActionsState(),
                    onActionsIntent = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = with(compose.density) {
        val dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        Rect(dp.left.toPx(), dp.top.toPx(), dp.right.toPx(), dp.bottom.toPx())
    }

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    private fun assertFullyInside(tag: String, outer: Rect) {
        val inner = bounds(tag)
        assertTrue(inner.height > 0f, "$tag has no height: $inner")
        assertTrue(
            inner.top >= outer.top - 1f && inner.bottom <= outer.bottom + 1f,
            "$tag at $inner is not fully inside the window $outer",
        )
    }

    @Test
    fun `a short window keeps the timeline usable and the cards reachable`() {
        val forms = formsOf(2)
        setChat(forms)
        val window = bounds("placeholder:$route")
        val timeline = bounds(ChatSlotTags.TIMELINE)

        val minimum = px(min(120.dp, with(compose.density) { window.height.toDp() } / 3))
        assertTrue(timeline.height >= minimum - 1f, "timeline is ${timeline.height}px, under $minimum px in $window")

        assertFullyInside(ChatSlotTags.SWITCHER, window)
        assertFullyInside(ChatSlotTags.COMPOSER, window)

        val area = bounds(ChatSlotTags.INTERACTIONS)
        val cards = listOf(ChatSlotTags.permission(permission.requestId)) + forms.cards.map { ChatSlotTags.form(it.formId) }
        cards.forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val card = bounds(tag)
            assertTrue(card.top < area.bottom && card.bottom > area.top, "$tag at $card is outside the cards' area $area")
        }

        compose.onNodeWithTag(timelineItemTag(latest.key)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun `a tall window gives the cards half the height and the timeline the rest`() {
        setChat(formsOf(8))
        val window = bounds("placeholder:$route")
        val usage = bounds(ChatSlotTags.USAGE)
        val timeline = bounds(ChatSlotTags.TIMELINE)
        val area = bounds(ChatSlotTags.INTERACTIONS)
        val composer = bounds(ChatSlotTags.COMPOSER)

        assertTrue(abs(area.height - window.height / 2) <= 1f, "cards take ${area.height}px, not half of $window")
        assertTrue(abs(timeline.top - usage.bottom) <= 1f, "timeline at $timeline does not start under the usage bar $usage")
        assertTrue(abs(timeline.bottom - area.top) <= 1f, "timeline at $timeline does not reach the cards at $area")
        // The switcher's chips sit inside the composer's card.
        assertTrue(abs(area.bottom - composer.top) <= 1f, "cards at $area do not sit on the composer at $composer")
        assertFullyInside(ChatSlotTags.COMPOSER, window)
        assertFullyInside(ChatSlotTags.SWITCHER, window)
        compose.onNodeWithTag(timelineItemTag(latest.key)).assertIsDisplayed()
    }
}
