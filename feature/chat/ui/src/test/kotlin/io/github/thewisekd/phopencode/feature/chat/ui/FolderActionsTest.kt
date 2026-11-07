package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import io.github.thewisekd.phopencode.feature.chat.ui.actions.assertTouchSizeIsAtLeast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A made-up folder: no real project or path. */
private const val FOLDER = "/work/sample"

/** The texts of the chat's files and git buttons in one language. */
internal data class ExpectedFolderStrings(val openFiles: String, val openGit: String)

/**
 * The chat's buttons to its session's folder, in its top bar: named for a screen reader, full
 * touch targets, disabled until the folder is known, and sending the actions presenter's intents.
 * A subclass sets the language and its texts.
 */
abstract class FolderActionsContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedFolderStrings

    private val route = ChatRoute.Chat(sessionId = "ses_1")
    private val intents = mutableListOf<ActionsIntent>()
    private lateinit var actions: MutableState<ActionsState>

    private fun setChat(initial: ActionsState) {
        actions = mutableStateOf(initial)
        compose.setContent {
            PhopencodeTheme {
                ChatScreen(
                    route = route,
                    status = StatusState(ChatStatus.Idle),
                    onStatusIntent = {},
                    usage = UsageState(),
                    onUsageIntent = {},
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
                    subagents = SubagentState(),
                    onSubagentIntent = {},
                    palette = PaletteState(),
                    onPaletteIntent = {},
                    switcher = SwitcherState(),
                    onSwitcherIntent = {},
                    actions = actions.value,
                    onActionsIntent = { intents += it },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the buttons are named and open the folder once it is known`() {
        setChat(ActionsState(directory = FOLDER))

        compose.onNodeWithTag(ChatSlotTags.OPEN_FILES)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assert(hasContentDescription(strings.openFiles))
            .assertTouchSizeIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag(ChatSlotTags.OPEN_GIT)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assert(hasContentDescription(strings.openGit))
            .assertTouchSizeIsAtLeast(48.dp)
            .performClick()
        compose.waitForIdle()

        assertEquals(listOf<ActionsIntent>(ActionsIntent.OpenFiles, ActionsIntent.OpenGit), intents)
    }

    @Test
    fun `the buttons keep their place but cannot be tapped before the folder is known`() {
        setChat(ActionsState())

        compose.onNodeWithTag(ChatSlotTags.OPEN_FILES).assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.onNodeWithTag(ChatSlotTags.OPEN_GIT).assertIsDisplayed().assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(emptyList(), intents)

        actions.value = ActionsState(directory = FOLDER)
        compose.waitForIdle()
        compose.onNodeWithTag(ChatSlotTags.OPEN_FILES).assertIsEnabled()
        compose.onNodeWithTag(ChatSlotTags.OPEN_GIT).assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "+w320dp-h640dp")
    fun `on a narrow phone the top bar keeps every button whole and the usage bar`() {
        setChat(ActionsState(enabled = true, directory = FOLDER))

        compose.onNodeWithTag(ChatSlotTags.USAGE).assertIsDisplayed()
        listOf(ChatSlotTags.OPEN_FILES, ChatSlotTags.OPEN_GIT).forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTouchSizeIsAtLeast(48.dp)
        }
        compose.onNodeWithTag(ChatSlotTags.ACTIONS).assertIsDisplayed()
    }
}

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishFolderActionsTest : FolderActionsContract() {
    override val strings = ExpectedFolderStrings(openFiles = "Open files", openGit = "Open git")
}

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "tr")
class TurkishFolderActionsTest : FolderActionsContract() {
    override val strings = ExpectedFolderStrings(openFiles = "Dosyaları aç", openGit = "Git'i aç")
}
