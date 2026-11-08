package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentNode
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.actions.assertTouchSizeIsAtLeast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A made-up session title, with Turkish letters so a lost character would show. */
private const val TITLE = "Giriş testini düzelt"

/** The texts of the chat's top bar in one language. */
internal data class ExpectedTopBarStrings(
    val fallbackTitle: String,
    val back: String,
    val actions: String,
    /** The compact badge's full name for a screen reader, with one subagent running. */
    val oneRunning: String,
)

/**
 * The chat's top bar: the session's title as a heading over a usage line that opens the usage page,
 * then the subagent badge, files, git and the action menu as icons, with a way back when there is
 * one. A subclass sets the language and its texts.
 */
abstract class ChatTopBarContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedTopBarStrings

    private val route = ChatRoute.Chat(sessionId = "ses_1")
    private val usageIntents = mutableListOf<UsageIntent>()
    private var backs = 0
    private lateinit var subagents: MutableState<SubagentState>

    private fun setChat(initial: SubagentState, withBack: Boolean = true) {
        subagents = mutableStateOf(initial)
        compose.setContent {
            PhopencodeTheme {
                ChatScreen(
                    route = route,
                    status = StatusState(ChatStatus.Idle),
                    onStatusIntent = {},
                    usage = UsageState(),
                    onUsageIntent = { usageIntents += it },
                    timeline = TimelineState(loading = false),
                    onTimelineIntent = {},
                    reasoning = ReasoningState(),
                    onReasoningIntent = {},
                    tool = ToolState(),
                    onToolIntent = {},
                    composer = ComposerState(),
                    onComposerIntent = {},
                    forms = FormsState(),
                    onFormIntent = {},
                    permissions = PermissionsState(),
                    onPermissionIntent = {},
                    subagents = subagents.value,
                    onSubagentIntent = {},
                    palette = PaletteState(),
                    onPaletteIntent = {},
                    switcher = SwitcherState(),
                    onSwitcherIntent = {},
                    actions = ActionsState(enabled = true, directory = "/work/sample"),
                    onActionsIntent = {},
                    onBack = if (withBack) ({ backs++ }) else null,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertTitle(text: String) {
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ChatSlotTags.USAGE)), useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(isHeading())
    }

    @Test
    fun `the title is the session's, as a heading, and says Chat while unknown`() {
        setChat(SubagentState())
        assertTitle(strings.fallbackTitle)

        subagents.value = SubagentState(loading = false, title = "   ")
        compose.waitForIdle()
        assertTitle(strings.fallbackTitle)

        subagents.value = SubagentState(loading = false, title = TITLE)
        compose.waitForIdle()
        assertTitle(TITLE)
    }

    @Test
    fun `tapping the title or its usage line opens the usage page`() {
        setChat(SubagentState(loading = false, title = TITLE))

        compose.onNodeWithTag(ChatSlotTags.USAGE).assertTouchSizeIsAtLeast(48.dp).performClick()
        compose.waitForIdle()

        assertEquals(listOf<UsageIntent>(UsageIntent.OpenDetail), usageIntents)
    }

    @Test
    fun `the back arrow is named, big enough to tap, and goes back`() {
        setChat(SubagentState(), withBack = true)

        compose.onNodeWithTag(ChatSlotTags.BACK)
            .assert(hasContentDescription(strings.back))
            .assertTouchSizeIsAtLeast(48.dp)
            .performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun `without a way back there is no back arrow`() {
        setChat(SubagentState(), withBack = false)

        compose.onNodeWithTag(ChatSlotTags.BACK).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+w412dp-h891dp")
    fun `on a 412 dp phone the title gets most of the bar, beside a compact badge read in full`() {
        val running = SubagentNode(SessionId("ses_2"), status = SubagentStatus.Running)
        setChat(SubagentState(loading = false, title = TITLE, subagents = listOf(running)))

        val bar = compose.onNodeWithTag("placeholder:$route").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag(ChatSlotTags.USAGE).fetchSemanticsNode().boundsInRoot
        assertTrue(title.width >= bar.width * 0.55f, "the title takes ${title.width} of ${bar.width}")
        compose.onNodeWithTag(ChatSlotTags.SUBAGENTS)
            .assert(hasContentDescription(strings.oneRunning))
            .assertTouchSizeIsAtLeast(48.dp)
        listOf(ChatSlotTags.BACK, ChatSlotTags.OPEN_FILES, ChatSlotTags.OPEN_GIT).forEach {
            compose.onNodeWithTag(it).assertTouchSizeIsAtLeast(48.dp)
        }
    }

    @Test
    fun `the title comes first, then the badge, files, git and the menu as a named icon`() {
        val running = SubagentNode(SessionId("ses_2"), status = SubagentStatus.Running)
        setChat(SubagentState(loading = false, title = TITLE, subagents = listOf(running)))

        compose.onNodeWithContentDescription(strings.actions).assertIsDisplayed().assertTouchSizeIsAtLeast(48.dp)
        val order = listOf(
            ChatSlotTags.BACK,
            ChatSlotTags.USAGE,
            ChatSlotTags.SUBAGENTS,
            ChatSlotTags.OPEN_FILES,
            ChatSlotTags.OPEN_GIT,
            ChatSlotTags.ACTIONS,
        ).map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        order.zipWithNext().forEach { (before, after) ->
            assertTrue(before.right <= after.left + 1f, "$before is not before $after")
        }
    }
}

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishChatTopBarTest : ChatTopBarContract() {
    override val strings = ExpectedTopBarStrings(
        fallbackTitle = "Chat",
        back = "Go back",
        actions = "Session actions",
        oneRunning = "1 running",
    )
}

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishChatTopBarTest : ChatTopBarContract() {
    override val strings = ExpectedTopBarStrings(
        fallbackTitle = "Sohbet",
        back = "Geri dön",
        actions = "Oturum işlemleri",
        oneRunning = "1 çalışıyor",
    )
}
