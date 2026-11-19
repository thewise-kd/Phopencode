package io.github.thewisekd.phopencode.feature.chat.ui.actions

import androidx.compose.material3.Surface
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.assertCopiedNotice
import io.github.thewisekd.phopencode.feature.chat.ui.timeline.clipboardText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** The texts of the session menu and a prompt's actions in one language. */
internal data class ExpectedActionsStrings(
    val menuButton: String,
    val menuTitle: String,
    val close: String,
    val compact: String,
    val compactDetail: String,
    val fork: String,
    val forkDetail: String,
    val preview: String,
    val previewDetail: String,
    val busy: String,
    val compactTitle: String,
    val compactMessage: String,
    val compactConfirm: String,
    val forkTitle: String,
    val forkMessage: String,
    val forkHereTitle: String,
    val forkHereMessage: String,
    val forkConfirm: String,
    val cancel: String,
    val compacting: String,
    val forking: String,
    val creating: String,
    val error: (SessionAction, reason: String) -> String,
    val reasons: Map<ChatFailure, String>,
    val turnChanges: String,
    val forkHere: String,
    val closeActions: String,
    /** A prompt's Copy action, and the short notice after it on Android 12L and below. */
    val copy: String,
    val copied: String,
)

/** What the actions say, the same in each language; a subclass sets the language and its texts. */
abstract class ActionsLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedActionsStrings

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    /** The merged node, as a screen reader reads a button. */
    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    /** The actions a test draws once and then changes; content can be set only once per test. */
    private var shown: ActionsHarness? = null

    private fun show(state: ActionsState, withMessage: Boolean = false) {
        val current = shown
        if (current == null) shown = compose.setActions(state, withMessage) else current.state.value = state
        compose.waitForIdle()
    }

    @Test
    fun `the menu names its parts`() {
        show(READY.copy(menuOpen = true))
        assertEquals(strings.menuButton, merged(ActionsTags.MENU_BUTTON).description())
        assertEquals(strings.menuTitle, node(ActionsTags.MENU_TITLE).text())
        assertEquals(strings.close, merged(ActionsTags.MENU_CLOSE).text())
        merged(ActionsTags.COMPACT).assert(hasText(strings.compact)).assert(hasText(strings.compactDetail))
        merged(ActionsTags.FORK).assert(hasText(strings.fork)).assert(hasText(strings.forkDetail))
        merged(ACTIONS_PREVIEW_TAG).assert(hasText(strings.preview)).assert(hasText(strings.previewDetail))

        show(READY.copy(menuOpen = true, running = SessionAction.Compact))
        assertEquals(strings.busy, node(ActionsTags.BUSY).text())
    }

    @Test
    fun `each confirmation says what happens`() {
        show(READY.copy(confirming = SessionAction.Compact))
        assertEquals(strings.compactTitle, node(ActionsTags.CONFIRM_TITLE).text())
        assertEquals(strings.compactMessage, node(ActionsTags.CONFIRM_MESSAGE).text())
        assertEquals(strings.compactConfirm, merged(ActionsTags.CONFIRM).text())
        assertEquals(strings.cancel, merged(ActionsTags.CANCEL).text())

        show(READY.copy(confirming = SessionAction.Fork(null)))
        assertEquals(strings.forkTitle, node(ActionsTags.CONFIRM_TITLE).text())
        assertEquals(strings.forkMessage, node(ActionsTags.CONFIRM_MESSAGE).text())
        assertEquals(strings.forkConfirm, merged(ActionsTags.CONFIRM).text())

        show(READY.copy(confirming = SessionAction.Fork(PROMPT)))
        assertEquals(strings.forkHereTitle, node(ActionsTags.CONFIRM_TITLE).text())
        assertEquals(strings.forkHereMessage, node(ActionsTags.CONFIRM_MESSAGE).text())
        assertEquals(strings.forkConfirm, merged(ActionsTags.CONFIRM).text())
    }

    @Test
    fun `the status is named`() {
        show(READY.copy(compacting = true))
        assertEquals(strings.compacting, node(ActionsTags.STATUS).text())

        show(READY.copy(running = SessionAction.Fork(null)))
        assertEquals(strings.forking, node(ActionsTags.STATUS).text())

        show(READY.copy(running = SessionAction.NewSession))
        assertEquals(strings.creating, node(ActionsTags.STATUS).text())
    }

    @Test
    fun `a failure says what failed and why`() {
        assertTrue(strings.reasons.keys == ChatFailure.entries.toSet())
        val actions = listOf(SessionAction.Compact, SessionAction.Fork(null), SessionAction.Fork(PROMPT), SessionAction.NewSession)
        for (action in actions) {
            for ((reason, text) in strings.reasons) {
                show(READY.copy(error = ActionError(action, reason)))
                assertEquals(strings.error(action, text), merged(ActionsTags.ERROR).text(), "$action $reason")
            }
        }
    }

    @Test
    fun `a prompt's actions are named`() {
        show(READY, withMessage = true)
        assertEquals(strings.turnChanges, merged(ActionsTags.TURN_CHANGES).text())
        assertEquals(strings.forkHere, merged(ActionsTags.FORK_HERE).text())
        assertEquals(strings.closeActions, merged(ActionsTags.MESSAGE_CLOSE).description())
    }

    @Test
    fun `a prompt's copy puts its text on the clipboard as typed and closes the actions`() {
        val typed = "  Testleri çalıştır\n- ve **düzelt**  "
        var dismissed = 0
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface {
                    MessageActions(PROMPT, READY, onIntent = {}, onDismiss = { dismissed++ }, text = typed)
                }
            }
        }
        compose.waitForIdle()

        // Drawn alone, in no scrolling container, so nothing to scroll to.
        val copy = compose.onNodeWithTag(ActionsTags.COPY)
        assertEquals(strings.copy, copy.text())
        copy.assertTouchSizeIsAtLeast(48.dp).performClick()
        compose.waitForIdle()

        assertEquals(typed, clipboardText())
        assertCopiedNotice(strings.copied)
        assertEquals(1, dismissed)
    }

    @Test
    fun `without a prompt's text there is no copy action`() {
        show(READY, withMessage = true)

        compose.onNodeWithTag(ActionsTags.COPY).assertDoesNotExist()
    }
}
