package io.github.thewisekd.phopencode.feature.chat.ui.permission

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the card draws and sends, in English: the texts of both languages are in `PermissionLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class PermissionBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private fun absent(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    // What is asked

    @Test
    fun `the action and every resource are spelled out verbatim`() {
        val multiLine = "cd app &&\n  ./gradlew çalıştır"
        compose.setPermission(bashCard(resources = listOf(COMMAND, multiLine, "src/ödev.kt")))

        assertTrue(node(PERMISSION_ACTION_TAG).text().contains("bash"))
        assertEquals(COMMAND, node(permissionResourceTag(0)).text())
        assertEquals(multiLine, node(permissionResourceTag(1)).text())
        assertEquals("src/ödev.kt", node(permissionResourceTag(2)).text())
        absent(permissionResourceTag(3))
        absent(PERMISSION_NO_RESOURCES_TAG)
    }

    @Test
    fun `an action the server added is still named as the server names it`() {
        compose.setPermission(bashCard().copy(action = "mcp_yeni-araç"))

        assertTrue(node(PERMISSION_ACTION_TAG).text().contains("mcp_yeni-araç"))
        absent(PERMISSION_ACTION_MEANING_TAG)
    }

    @Test
    fun `a known action is also said in words`() {
        compose.setPermission(bashCard())

        node(PERMISSION_ACTION_MEANING_TAG).assertIsDisplayed()
    }

    @Test
    fun `a request without resources says so`() {
        compose.setPermission(bashCard(resources = emptyList()))

        node(PERMISSION_NO_RESOURCES_TAG).assertIsDisplayed()
        absent(permissionResourceTag(0))
    }

    @Test
    fun `the server's own wording shows when it has some`() {
        val harness = compose.setPermission(bashCard().copy(message = "Komutu çalıştırayım mı?"))
        assertEquals("Komutu çalıştırayım mı?", node(PERMISSION_MESSAGE_TAG).text())

        harness.state.value = bashCard().copy(message = "  ")
        compose.waitForIdle()
        absent(PERMISSION_MESSAGE_TAG)

        harness.state.value = bashCard()
        compose.waitForIdle()
        absent(PERMISSION_MESSAGE_TAG)
    }

    @Test
    fun `a subagent's request names the subagent's session`() {
        val harness = compose.setPermission(bashCard())
        val own = node(PERMISSION_ASKER_TAG).text()
        assertTrue(!own.contains(CHILD.value))

        harness.state.value = bashCard().copy(sessionId = CHILD, fromSubagent = true)
        compose.waitForIdle()
        val child = node(PERMISSION_ASKER_TAG).text()
        assertTrue(child.contains(CHILD.value), child)
        assertTrue(child != own)
    }

    // Deciding

    @Test
    fun `allow once goes at once`() {
        val harness = compose.setPermission(bashCard())

        click(PERMISSION_ONCE_TAG)

        assertEquals(listOf<PermissionIntent>(PermissionIntent.AllowOnce(REQUEST)), harness.intents)
    }

    @Test
    fun `allow always only asks for its confirmation`() {
        val harness = compose.setPermission(bashCard())

        click(PERMISSION_ALWAYS_TAG)

        assertEquals(listOf<PermissionIntent>(PermissionIntent.AllowAlways(REQUEST)), harness.intents)
        // The state decides whether the confirmation shows; the card does not open it by itself.
        absent(PERMISSION_CONFIRM_ALWAYS_TAG)
    }

    @Test
    fun `reject opens the rejection message`() {
        val harness = compose.setPermission(bashCard())

        click(PERMISSION_REJECT_TAG)

        assertEquals(listOf<PermissionIntent>(PermissionIntent.StartReject(REQUEST)), harness.intents)
    }

    // Always, behind its confirmation

    @Test
    fun `the confirmation lists what always would remember and replaces the other choices`() {
        compose.setPermission(bashCard(save = listOf("rm *", "git push*")).copy(confirmingAlways = true))

        node(PERMISSION_CONFIRM_TAG).assertIsDisplayed()
        assertEquals("rm *", node(permissionSaveTag(0)).text())
        assertEquals("git push*", node(permissionSaveTag(1)).text())
        absent(permissionSaveTag(2))
        absent(PERMISSION_WILDCARD_TAG)
        absent(PERMISSION_ONCE_TAG)
        absent(PERMISSION_ALWAYS_TAG)
        absent(PERMISSION_REJECT_TAG)
        // What is asked stays in view while confirming.
        assertEquals(COMMAND, node(permissionResourceTag(0)).text())
    }

    @Test
    fun `a rule for everything is warned about`() {
        val harness = compose.setPermission(bashCard(save = listOf("*")).copy(confirmingAlways = true))
        node(PERMISSION_WILDCARD_TAG).assertIsDisplayed()

        harness.state.value = bashCard(save = listOf("src/**", " ** ")).copy(confirmingAlways = true)
        compose.waitForIdle()
        node(PERMISSION_WILDCARD_TAG).assertIsDisplayed()
    }

    @Test
    fun `a confirmation without a rule still says what always does`() {
        compose.setPermission(bashCard(save = emptyList()).copy(confirmingAlways = true))

        node(PERMISSION_CONFIRM_TAG).assertIsDisplayed()
        absent(permissionSaveTag(0))
    }

    @Test
    fun `confirming sends always and cancelling takes it back`() {
        val harness = compose.setPermission(bashCard().copy(confirmingAlways = true))

        click(PERMISSION_CONFIRM_ALWAYS_TAG)
        click(PERMISSION_CANCEL_ALWAYS_TAG)

        assertEquals(
            listOf<PermissionIntent>(PermissionIntent.ConfirmAlways(REQUEST), PermissionIntent.CancelAlways(REQUEST)),
            harness.intents,
        )
    }

    // Reject with a message

    @Test
    fun `the rejection message shows the state's text and sends each change`() {
        val harness = compose.setPermission(bashCard().copy(rejecting = true, rejectMessage = "Hayır"))

        assertEquals("Hayır", node(PERMISSION_REJECT_FIELD_TAG).editableText())
        compose.onNodeWithTag(PERMISSION_REJECT_FIELD_TAG).performScrollTo().performTextReplacement("Önce testleri çalıştır")

        assertEquals(
            listOf<PermissionIntent>(PermissionIntent.RejectMessageChanged(REQUEST, "Önce testleri çalıştır")),
            harness.intents,
        )
        absent(PERMISSION_ONCE_TAG)
        absent(PERMISSION_ALWAYS_TAG)
    }

    @Test
    fun `sending the rejection and cancelling it`() {
        val harness = compose.setPermission(bashCard().copy(rejecting = true))

        click(PERMISSION_SEND_REJECT_TAG)
        click(PERMISSION_CANCEL_REJECT_TAG)

        assertEquals(
            listOf<PermissionIntent>(PermissionIntent.Reject(REQUEST), PermissionIntent.CancelReject(REQUEST)),
            harness.intents,
        )
    }

    // On its way, and failed

    @Test
    fun `while a decision is on its way nothing else can be sent`() {
        val harness = compose.setPermission(bashCard().copy(replying = PermissionDecision.Once))

        node(PERMISSION_SENDING_TAG).assertIsDisplayed()
        node(PERMISSION_ONCE_TAG).assertIsNotEnabled()
        node(PERMISSION_ALWAYS_TAG).assertIsNotEnabled()
        node(PERMISSION_REJECT_TAG).assertIsNotEnabled()

        harness.state.value = bashCard().copy(confirmingAlways = true, replying = PermissionDecision.Always)
        compose.waitForIdle()
        node(PERMISSION_CONFIRM_ALWAYS_TAG).assertIsNotEnabled()
        node(PERMISSION_CANCEL_ALWAYS_TAG).assertIsNotEnabled()

        harness.state.value = bashCard().copy(rejecting = true, replying = PermissionDecision.Reject)
        compose.waitForIdle()
        node(PERMISSION_SEND_REJECT_TAG).assertIsNotEnabled()
        node(PERMISSION_CANCEL_REJECT_TAG).assertIsNotEnabled()
        node(PERMISSION_REJECT_FIELD_TAG).assertIsNotEnabled()

        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a card at rest has its choices enabled and nothing on its way`() {
        compose.setPermission(bashCard())

        node(PERMISSION_ONCE_TAG).assertIsEnabled()
        absent(PERMISSION_SENDING_TAG)
        absent(PERMISSION_ERROR_TAG)
    }

    @Test
    fun `a failed reply shows and can be dismissed`() {
        val harness = compose.setPermission(
            bashCard().copy(error = PermissionError(PermissionDecision.Once, ChatFailure.Unreachable)),
        )

        node(PERMISSION_ERROR_TAG).assertIsDisplayed()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(PERMISSION_ERROR_TAG)), useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        assertEquals(listOf<PermissionIntent>(PermissionIntent.DismissError(REQUEST)), harness.intents)
        // The choices stay, so the decision can be sent again.
        node(PERMISSION_ONCE_TAG).assertIsEnabled()
    }

    // Layout

    /** The card sits in the chat's interaction area, which scrolls itself and is capped at half the screen. */
    @Test
    fun `the card never scrolls vertically`() {
        compose.setPermission(
            bashCard(resources = List(40) { "line $it ".repeat(20) }, save = List(20) { "*" })
                .copy(message = "m ".repeat(500), confirmingAlways = true),
        )
        compose.waitForIdle()

        val inCard = hasTestTag(CARD_TAG) or hasAnyAncestor(hasTestTag(CARD_TAG))
        val nodes = compose.onAllNodes(inCard, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        val vertical = nodes.filter { SemanticsProperties.VerticalScrollAxisRange in it.config }
        assertTrue(vertical.isEmpty(), "nodes that scroll vertically: ${vertical.map { it.config }}")
    }

    // The list error

    @Test
    fun `the list error says what failed and retries`() {
        val harness = compose.setPermissionListError(ChatFailure.ServerFailed)

        compose.onNodeWithTag(LIST_ERROR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(LIST_ERROR_TAG)), useUnmergedTree = true)
            .performScrollTo()
            .performClick()

        assertEquals(listOf<PermissionIntent>(PermissionIntent.Retry), harness.intents)
    }
}
