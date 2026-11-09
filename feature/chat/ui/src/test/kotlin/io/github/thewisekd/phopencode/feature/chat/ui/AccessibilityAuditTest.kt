package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningCardState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolCardStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolSection
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.actions.ActionsTags
import io.github.thewisekd.phopencode.feature.chat.ui.form.FormHarness
import io.github.thewisekd.phopencode.feature.chat.ui.form.question
import io.github.thewisekd.phopencode.feature.chat.ui.form.single
import io.github.thewisekd.phopencode.feature.chat.ui.form.text
import io.github.thewisekd.phopencode.feature.chat.ui.palette.PaletteHarness
import io.github.thewisekd.phopencode.feature.chat.ui.palette.SLASH
import io.github.thewisekd.phopencode.feature.chat.ui.permission.PermissionHarness
import io.github.thewisekd.phopencode.feature.chat.ui.permission.bashCard
import io.github.thewisekd.phopencode.feature.chat.ui.reasoning.ReasoningHarness
import io.github.thewisekd.phopencode.feature.chat.ui.reasoning.THOUGHT
import io.github.thewisekd.phopencode.feature.chat.ui.revert.DIFF_ROUTE
import io.github.thewisekd.phopencode.feature.chat.ui.revert.STAGED
import io.github.thewisekd.phopencode.feature.chat.ui.revert.TurnDiffScreen
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.LIST_ROUTE
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentListScreen
import io.github.thewisekd.phopencode.feature.chat.ui.switcher.SwitcherHarness
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.answer
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.user
import io.github.thewisekd.phopencode.feature.chat.ui.tool.ToolHarness
import io.github.thewisekd.phopencode.feature.chat.ui.tool.numberedLines
import io.github.thewisekd.phopencode.feature.chat.ui.tool.shellCard
import io.github.thewisekd.phopencode.feature.chat.ui.usage.DETAIL_ROUTE
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.thewisekd.phopencode.feature.chat.ui.revert.LOADED as REVERT_LOADED
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.LOADED as SUBAGENTS_LOADED
import io.github.thewisekd.phopencode.feature.chat.ui.switcher.loaded as switcherOpen
import io.github.thewisekd.phopencode.feature.chat.ui.usage.LOADED as USAGE_LOADED

/**
 * The chat and its pages, in their main states, at the largest font: every control is named and big
 * enough to tap, and no text is cut off. The cards are also drawn alone, opened up, as the chat
 * shows them only when the timeline has room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class AccessibilityAuditTest {
    @get:Rule
    val compose = createComposeRule()

    private val failure = ChatFailure.Unreachable

    /**
     * The top bar's icon buttons are narrower than 48 dp in the layout so that the session's title
     * keeps most of the bar; each still takes taps over 48 dp, as `ChatTopBarTest` checks.
     */
    private val narrowTopBar = setOf(
        ChatSlotTags.BACK,
        ChatSlotTags.SUBAGENTS,
        ChatSlotTags.OPEN_FILES,
        ChatSlotTags.OPEN_GIT,
        ActionsTags.MENU_BUTTON,
    )

    private fun audit(narrowByDesign: Set<String> = emptySet(), content: @Composable () -> Unit) {
        compose.setThemedAt { Surface(content = content) }
        compose.assertAccessible(narrowByDesign)
    }

    private fun chat(
        status: StatusState = StatusState(ChatStatus.Idle),
        timeline: TimelineState = TimelineState(loading = false),
        composer: ComposerState = ComposerState(enabled = true),
        forms: FormsState = FormsState(),
        permissions: PermissionsState = PermissionsState(),
    ) = audit(narrowTopBar) {
        ChatScreen(
            route = ChatRoute.Chat(sessionId = "ses_1"),
            status = status,
            onStatusIntent = {},
            usage = USAGE_LOADED,
            onUsageIntent = {},
            timeline = timeline,
            onTimelineIntent = {},
            reasoning = ReasoningState(),
            onReasoningIntent = {},
            tool = ToolState(),
            onToolIntent = {},
            composer = composer,
            onComposerIntent = {},
            forms = forms,
            onFormIntent = {},
            permissions = permissions,
            onPermissionIntent = {},
            subagents = SUBAGENTS_LOADED,
            onSubagentIntent = {},
            palette = PaletteState(),
            onPaletteIntent = {},
            switcher = SwitcherState(),
            onSwitcherIntent = {},
            actions = ActionsState(enabled = true, directory = "/work/sample"),
            onActionsIntent = {},
            onBack = {},
        )
    }

    @Test
    fun `an empty chat`() = chat()

    @Test
    fun `a chat while it loads`() = chat(status = StatusState(ChatStatus.Loading), timeline = TimelineState())

    @Test
    fun `a chat with messages, running`() = chat(
        status = StatusState(ChatStatus.Running),
        timeline = TimelineState(loading = false, items = listOf(user(1), answer(1), user(2))),
        composer = ComposerState(draft = "Bir şey daha", enabled = true, running = true),
    )

    @Test
    fun `a chat that lost its connection`() {
        chat(status = StatusState(ChatStatus.Disconnected))
        compose.assertErrorAnnounced(R.string.chat_status_disconnected)
    }

    @Test
    fun `a chat failing to read older messages`() {
        chat(
            timeline = TimelineState(loading = false, items = listOf(user(1), answer(1)), hasOlder = true, olderError = failure),
        )
        compose.assertErrorAnnounced(R.string.chat_timeline_older_failed)
    }

    @Test
    fun `a chat asking for a permission and an answer`() = chat(
        forms = FormsState(cards = listOf(question(single()))),
        permissions = PermissionsState(cards = listOf(bashCard())),
    )

    @Test
    fun `a chat failing to read its requests`() {
        chat(
            forms = FormsState(error = failure),
            permissions = PermissionsState(error = failure),
        )
        compose.assertErrorAnnounced(R.string.chat_form_reason_unreachable)
        compose.assertErrorAnnounced(R.string.chat_permission_reason_unreachable)
    }

    @Test
    fun `the subagents`() = audit { SubagentListScreen(LIST_ROUTE, SUBAGENTS_LOADED, {}) }

    @Test
    fun `no subagents`() = audit { SubagentListScreen(LIST_ROUTE, SubagentState(loading = false), {}) }

    @Test
    fun `the subagents failed`() {
        audit { SubagentListScreen(LIST_ROUTE, SubagentState(loading = false, error = failure), {}) }
        compose.assertErrorAnnounced(R.string.chat_subagent_reason_unreachable)
    }

    @Test
    fun `the usage`() = audit { UsageDetailScreen(DETAIL_ROUTE, USAGE_LOADED, {}) }

    @Test
    fun `the usage failed`() {
        audit { UsageDetailScreen(DETAIL_ROUTE, UsageState(loading = false, error = failure), {}) }
        compose.assertErrorAnnounced(R.string.chat_usage_reason_unreachable)
    }

    @Test
    fun `a turn's changes`() = audit { TurnDiffScreen(DIFF_ROUTE, REVERT_LOADED, {}) }

    @Test
    fun `a turn's changes staged for undoing`() = audit { TurnDiffScreen(DIFF_ROUTE, STAGED, {}) }

    @Test
    fun `a turn without changes`() = audit { TurnDiffScreen(DIFF_ROUTE, RevertState(loading = false, enabled = true), {}) }

    @Test
    fun `a turn's changes failed`() {
        audit { TurnDiffScreen(DIFF_ROUTE, RevertState(loading = false, loadError = failure), {}) }
        compose.assertErrorAnnounced(R.string.chat_revert_reason_unreachable)
    }

    @Test
    fun `an open tool card`() = audit {
        ToolHarness(shellCard(ToolCardStatus.Completed, input = "ls", output = numberedLines(5)).copy(expanded = ToolSection.entries.toSet())).Content()
    }

    @Test
    fun `an open reasoning card`() =
        audit { ReasoningHarness(ReasoningCardState(text = THOUGHT, expanded = true)).Content() }

    @Test
    fun `a permission card`() = audit { PermissionHarness(bashCard()).Content() }

    @Test
    fun `a form card`() = audit { FormHarness(question(single(), text())).Content() }

    @Test
    fun `the agent and model switcher`() = audit { SwitcherHarness(switcherOpen()).Content() }

    @Test
    fun `the command palette`() = audit { PaletteHarness(SLASH).Content() }
}
