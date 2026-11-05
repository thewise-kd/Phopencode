package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionCardState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionError
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the permission card in one language. */
internal data class ExpectedPermissionStrings(
    val title: String,
    val askerSelf: String,
    val askerSubagent: (session: String) -> String,
    val action: (String) -> String,
    val meanings: Map<String, String>,
    val noResources: String,
    val allowOnce: String,
    val allowAlways: String,
    val reject: String,
    val confirmTitle: String,
    val confirmSave: (action: String) -> String,
    val confirmNoSave: (action: String) -> String,
    val wildcard: (action: String) -> String,
    val confirmAlways: String,
    val cancel: String,
    val sending: Map<PermissionDecision, String>,
    val error: (PermissionDecision, reason: String) -> String,
    val reasons: Map<ChatFailure, String>,
    val listError: (reason: String) -> String,
    val listReasons: Map<ChatFailure, String>,
)

/** What the card says, the same in each language; a subclass sets the language and its texts. */
abstract class PermissionLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedPermissionStrings

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    /** The merged node, as a screen reader reads a button. */
    private fun merged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    /** The card a test draws once and then changes; content can be set only once per test. */
    private var shown: PermissionHarness? = null

    private fun show(state: PermissionCardState) {
        val current = shown
        if (current == null) shown = compose.setPermission(state) else current.state.value = state
        compose.waitForIdle()
    }

    @Test
    fun `the card says what is asked and by whom`() {
        show(bashCard())
        assertEquals(strings.title, node(PERMISSION_TITLE_TAG).text())
        assertEquals(strings.askerSelf, node(PERMISSION_ASKER_TAG).text())
        assertEquals(strings.action("bash"), node(PERMISSION_ACTION_TAG).text())

        show(bashCard().copy(sessionId = CHILD, fromSubagent = true))
        assertEquals(strings.askerSubagent(CHILD.value), node(PERMISSION_ASKER_TAG).text())

        show(bashCard(resources = emptyList()))
        assertEquals(strings.noResources, node(PERMISSION_NO_RESOURCES_TAG).text())
    }

    @Test
    fun `known actions are said in words`() {
        for ((action, meaning) in strings.meanings) {
            show(bashCard().copy(action = action))
            assertEquals(meaning, node(PERMISSION_ACTION_MEANING_TAG).text(), action)
        }
    }

    @Test
    fun `the choices are named`() {
        show(bashCard())
        assertEquals(strings.allowOnce, merged(PERMISSION_ONCE_TAG).text())
        assertEquals(strings.allowAlways, merged(PERMISSION_ALWAYS_TAG).text())
        assertEquals(strings.reject, merged(PERMISSION_REJECT_TAG).text())

        show(bashCard().copy(rejecting = true))
        assertEquals(strings.reject, merged(PERMISSION_SEND_REJECT_TAG).text())
        assertEquals(strings.cancel, merged(PERMISSION_CANCEL_REJECT_TAG).text())
    }

    @Test
    fun `the confirmation says what always means`() {
        show(bashCard(save = listOf("rm *")).copy(confirmingAlways = true))
        assertEquals(strings.confirmTitle, node(PERMISSION_CONFIRM_TITLE_TAG).text())
        assertEquals(strings.confirmSave("bash"), node(PERMISSION_CONFIRM_TAG).text())
        assertEquals(strings.confirmAlways, merged(PERMISSION_CONFIRM_ALWAYS_TAG).text())
        assertEquals(strings.cancel, merged(PERMISSION_CANCEL_ALWAYS_TAG).text())

        show(bashCard(save = emptyList()).copy(confirmingAlways = true))
        assertEquals(strings.confirmNoSave("bash"), node(PERMISSION_CONFIRM_TAG).text())

        show(bashCard(save = listOf("*")).copy(confirmingAlways = true))
        assertEquals(strings.wildcard("bash"), node(PERMISSION_WILDCARD_TAG).text())
    }

    @Test
    fun `a decision on its way is named`() {
        for ((decision, text) in strings.sending) {
            show(bashCard().copy(replying = decision))
            assertEquals(text, node(PERMISSION_SENDING_TAG).text(), "$decision")
        }
    }

    @Test
    fun `a failed reply says which decision failed and why`() {
        for (decision in PermissionDecision.entries) {
            for ((reason, text) in strings.reasons) {
                show(bashCard().copy(error = PermissionError(decision, reason)))
                assertEquals(strings.error(decision, text), merged(PERMISSION_ERROR_TAG).text(), "$decision $reason")
            }
        }
    }

    @Test
    fun `the list error says why`() {
        var harness: PermissionListErrorHarness? = null
        for ((reason, text) in strings.listReasons) {
            val current = harness
            if (current == null) harness = compose.setPermissionListError(reason) else current.error.value = reason
            compose.waitForIdle()
            assertEquals(strings.listError(text), merged(LIST_ERROR_TAG).text(), "$reason")
        }
    }
}
