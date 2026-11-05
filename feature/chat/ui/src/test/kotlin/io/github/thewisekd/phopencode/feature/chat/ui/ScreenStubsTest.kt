package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.thewisekd.phopencode.core.model.FormId
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
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
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.revert.TurnDiffScreen
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentListScreen
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
    private val interactionSlots = listOf(
        ChatSlotTags.SUBAGENTS,
        ChatSlotTags.ACTIONS,
        ChatSlotTags.PALETTE,
        ChatSlotTags.ATTACHMENTS,
        ChatSlotTags.SWITCHER,
    )
    private val call = TimelineItem.Tool(TimelineKey.tool(message, "call_1"), message, callId = "call_1", name = "bash")

    private val form = FormCardState(formId = FormId("frm_1"), sessionId = SessionId("ses_1"))
    private val childForm = FormCardState(formId = FormId("frm_2"), sessionId = SessionId("ses_2"), fromSubagent = true)
    private val permission = PermissionCardState(PermissionRequestId("per_1"), SessionId("ses_2"), fromSubagent = true, action = "bash")
    private val forms = FormsState(cards = listOf(form, childForm))
    private val permissions = PermissionsState(cards = listOf(permission))

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
                    forms = forms,
                    onFormIntent = {},
                    permissions = permissions,
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
        (interactionSlots + ChatSlotTags.form(form.formId) + ChatSlotTags.permission(permission.requestId)).forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
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
        interactionSlots.forEach { compose.onNodeWithTag(it).assertExists() }
    }

    @Test
    fun `every pending form and permission, a subagent's too, has a card slot`() {
        setChat(StatusState(ChatStatus.Idle), TimelineState(loading = false))

        compose.onNodeWithTag(ChatSlotTags.form(form.formId)).assertExists()
        compose.onNodeWithTag(ChatSlotTags.form(childForm.formId)).assertExists()
        compose.onNodeWithTag(ChatSlotTags.permission(permission.requestId)).assertExists()
    }

    @Test
    fun `a long press on a prompt opens its actions slot`() {
        val prompt = TimelineItem.User(TimelineKey.message(MessageId("msg_u1")), MessageId("msg_u1"), text = "Fix the build")
        setChat(StatusState(ChatStatus.Idle), TimelineState(loading = false, items = listOf(prompt)))
        compose.onNodeWithTag(ChatSlotTags.messageActions(prompt.key)).assertDoesNotExist()

        compose.onNodeWithText("Fix the build").performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(ChatSlotTags.messageActions(prompt.key)).assertExists()
    }

    @Test
    fun `a child session keeps its route's tag and places its parts`() {
        val child = ChatRoute.ChildSession(sessionId = "ses_2")
        compose.setContent {
            PhopencodeTheme {
                ChildSessionScreen(
                    route = child,
                    status = StatusState(ChatStatus.Idle),
                    onStatusIntent = {},
                    subagents = SubagentState(),
                    onSubagentIntent = {},
                    timeline = TimelineState(loading = false, items = listOf(thinking, call)),
                    onTimelineIntent = {},
                    reasoning = ReasoningState(cards = mapOf(thinking.key to ReasoningCardState(text = "plan"))),
                    onReasoningIntent = {},
                    tool = ToolState(),
                    onToolIntent = {},
                    forms = forms,
                    onFormIntent = {},
                    permissions = permissions,
                    onPermissionIntent = {},
                )
            }
        }

        compose.onNodeWithTag("placeholder:$child").assertIsDisplayed()
        listOf(ChatSlotTags.SUBAGENT_HEADER, ChatSlotTags.STATUS, ChatSlotTags.TIMELINE).forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithTag(ChatSlotTags.reasoning(thinking.key)).assertExists()
        compose.onNodeWithTag(ChatSlotTags.form(form.formId)).assertExists()
        compose.onNodeWithTag(ChatSlotTags.permission(permission.requestId)).assertExists()
        // A subagent's timeline is read only: no composer.
        compose.onNodeWithTag(ChatSlotTags.COMPOSER).assertDoesNotExist()
    }

    @Test
    fun `the subagent list keeps its route's tag`() {
        val list = ChatRoute.Subagents(sessionId = "ses_1")
        compose.setContent {
            PhopencodeTheme { SubagentListScreen(list, SubagentState(), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$list").assertIsDisplayed()
    }

    @Test
    fun `the turn diff page keeps its route's tag`() {
        val diff = ChatRoute.TurnDiff(sessionId = "ses_1", messageId = "msg_1")
        compose.setContent {
            PhopencodeTheme { TurnDiffScreen(diff, RevertState(), onIntent = {}) }
        }

        compose.onNodeWithTag("placeholder:$diff").assertIsDisplayed()
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
