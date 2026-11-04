package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineItem
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineKey
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSummary
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat keeps, on its root, the `placeholder:<route>` tag the app's navigation test looks for,
 * and places every part in its slot. The screen and card tasks fill the parts in; this holds for
 * them too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class ScreenStubsTest {
    @get:Rule
    val compose = createComposeRule()

    private val route = ChatRoute.Chat(sessionId = "ses_1")
    private val message = MessageId("msg_1")
    private val thinking = TimelineItem.Reasoning(TimelineKey.reasoning(message, 0), message)
    private val emptyThinking = TimelineItem.Reasoning(TimelineKey.reasoning(message, 2), message)
    private val call = TimelineItem.Tool(TimelineKey.tool(message, "call_1"), message, callId = "call_1", name = "bash")

    private fun setChat(status: StatusState, timeline: TimelineState = TimelineState()) {
        compose.setContent {
            PhopencodeTheme {
                ChatScreen(
                    route = route,
                    status = status,
                    onStatusIntent = {},
                    usage = UsageState(),
                    onUsageIntent = {},
                    timeline = timeline,
                    onTimelineIntent = {},
                    reasoning = ReasoningState(cards = mapOf(thinking.key to ReasoningCardState(text = "plan"))),
                    onReasoningIntent = {},
                    tool = ToolState(
                        cards = mapOf(
                            call.key to ToolCardState(
                                callId = "call_1",
                                name = "bash",
                                status = ToolCardStatus.Running,
                                summary = ToolSummary.Shell("ls"),
                            ),
                        ),
                    ),
                    onToolIntent = {},
                    composer = ComposerState(),
                    onComposerIntent = {},
                )
            }
        }
    }

    @Test
    fun `the chat keeps its route's tag while loading`() {
        setChat(StatusState())

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }

    @Test
    fun `a status that leaves nothing to show takes the chat`() {
        setChat(StatusState(ChatStatus.NotFound))

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        compose.onNodeWithTag(ChatSlotTags.STATUS).assertExists()
        compose.onNodeWithTag(ChatSlotTags.TIMELINE).assertDoesNotExist()
        compose.onNodeWithTag(ChatSlotTags.COMPOSER).assertDoesNotExist()
        compose.onNodeWithTag(ChatSlotTags.USAGE).assertDoesNotExist()
    }

    @Test
    fun `a loaded chat places every part in its slot`() {
        setChat(
            StatusState(ChatStatus.Idle),
            TimelineState(loading = false, items = listOf(thinking, emptyThinking, call)),
        )

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
        listOf(ChatSlotTags.USAGE, ChatSlotTags.STATUS, ChatSlotTags.TIMELINE, ChatSlotTags.COMPOSER).forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithTag(ChatSlotTags.reasoning(thinking.key)).assertExists()
        compose.onNodeWithTag(ChatSlotTags.tool(call.key)).assertExists()
        // A reasoning without a card, such as an empty one, draws nothing.
        compose.onNodeWithTag(ChatSlotTags.reasoning(emptyThinking.key)).assertDoesNotExist()
    }

    @Test
    fun `the usage page keeps its route's tag`() {
        val detail = ChatRoute.TokenUsage(sessionId = "ses_1")
        compose.setContent {
            PhopencodeTheme { UsageDetailScreen(detail, UsageState(), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$detail").assertIsDisplayed()
    }
}
