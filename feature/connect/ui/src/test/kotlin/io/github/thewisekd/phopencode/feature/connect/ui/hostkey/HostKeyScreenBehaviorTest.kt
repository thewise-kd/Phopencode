package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val APPROVE = "Approve and connect"
private const val REJECT = "Reject"
private const val CHANGED_REJECT = "Reject and stay disconnected"
private const val CHANGED_ACCEPT = "Trust the new key…"
private const val CHANGED_TITLE = "Warning: this server's key has changed"
private const val CONFIRM_TITLE = "Replace the approved key?"
private const val CONFIRM_ACCEPT = "Replace key"
private const val CANCEL = "Cancel"
private const val DISMISS = "Dismiss error"

/** What the host key screen sends, in English: the texts of both languages are in `HostKeyScreenLanguageContract`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class HostKeyScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun button(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text).performScrollTo()

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun openConfirmation() {
        button(CHANGED_ACCEPT).performClick()
        compose.onNodeWithText(CONFIRM_TITLE).assertExists()
    }

    // First seen

    @Test
    fun `approve sends Approve`() {
        val harness = compose.setHostKeyScreen(asking(firstSeen))

        button(APPROVE).performClick()

        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.Approve), harness.intents)
    }

    @Test
    fun `reject sends Reject`() {
        val harness = compose.setHostKeyScreen(asking(firstSeen))

        button(REJECT).performClick()

        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.Reject), harness.intents)
    }

    @Test
    fun `a first seen key shows no warning`() {
        compose.setHostKeyScreen(asking(firstSeen))

        compose.onNodeWithTag(HOSTKEY_WARNING_TAG).assertDoesNotExist()
        compose.onNodeWithText(CHANGED_ACCEPT).assertDoesNotExist()
    }

    // Changed

    @Test
    fun `rejecting a changed key sends Reject at once`() {
        val harness = compose.setHostKeyScreen(asking(changed))

        button(CHANGED_REJECT).performClick()

        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.Reject), harness.intents)
    }

    @Test
    fun `accepting a changed key sends nothing until confirmed`() {
        val harness = compose.setHostKeyScreen(asking(changed))

        openConfirmation()
        assertTrue(harness.intents.isEmpty())

        inDialog(CONFIRM_ACCEPT).performClick()

        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.AcceptChangedKey), harness.intents)
        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
    }

    @Test
    fun `cancelling the confirmation sends nothing and keeps the key unaccepted`() {
        val harness = compose.setHostKeyScreen(asking(changed))

        openConfirmation()
        inDialog(CANCEL).performClick()

        assertTrue(harness.intents.isEmpty())
        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        button(CHANGED_REJECT).assertIsEnabled()
    }

    @Test
    fun `a confirmation does not outlive the key it was asked for`() {
        val harness = compose.setHostKeyScreen(asking(changed))
        openConfirmation()

        val other = HostKeyFingerprint("SHA256:not-a-real-fingerprint-other-2222222222222222")
        harness.state.value = asking(HostKeyRequest.Changed(KEY_TYPE, other, approved = APPROVED_FINGERPRINT))

        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a confirmation does not survive recreation onto another key`() {
        val harness = HostKeyScreenHarness(asking(changed), darkTheme = false, height = null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { harness.Content() }
        openConfirmation()

        val other = HostKeyFingerprint("SHA256:not-a-real-fingerprint-other-2222222222222222")
        harness.state.value = asking(HostKeyRequest.Changed(KEY_TYPE, other, approved = APPROVED_FINGERPRINT))
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        compose.onNodeWithText(CONFIRM_ACCEPT).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty())

        // Accepting the new key takes a fresh confirmation.
        openConfirmation()
        inDialog(CONFIRM_ACCEPT).performClick()
        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.AcceptChangedKey), harness.intents)
    }

    @Test
    fun `a recreation closes the confirmation even for the same key`() {
        val harness = HostKeyScreenHarness(asking(changed), darkTheme = false, height = null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { harness.Content() }
        openConfirmation()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(CONFIRM_TITLE).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `a changed key cannot be approved like a new one`() {
        compose.setHostKeyScreen(asking(changed))

        compose.onNodeWithText(APPROVE).assertDoesNotExist()
    }

    @Test
    fun `reject comes before accept on a changed key`() {
        compose.setHostKeyScreen(asking(changed))

        assertRejectComesFirst()
    }

    /** A screen so short that both buttons start below the fold: the order must not depend on scrolling. */
    @Test
    fun `reject comes before accept on a changed key on a short screen`() {
        compose.setHostKeyScreen(asking(changed), height = 240.dp)
        compose.onNodeWithText(CHANGED_REJECT).assertIsNotDisplayed()
        compose.onNodeWithText(CHANGED_ACCEPT).assertIsNotDisplayed()

        assertRejectComesFirst()
    }

    /**
     * Compares the unclipped layout positions, read without scrolling: both buttons sit in the same
     * scrolling column, so any scroll offset moves them alike.
     */
    private fun assertRejectComesFirst() {
        val reject = compose.onNodeWithText(CHANGED_REJECT).fetchSemanticsNode().positionInRoot
        val accept = compose.onNodeWithText(CHANGED_ACCEPT).fetchSemanticsNode().positionInRoot

        assertTrue(reject.y < accept.y, "the safe choice must come first: reject at $reject, accept at $accept")
    }

    @Test
    fun `the warning is drawn in the theme's error colours in light theme`() {
        assertWarningUsesErrorColours(darkTheme = false)
    }

    @Test
    fun `the warning is drawn in the theme's error colours in dark theme`() {
        assertWarningUsesErrorColours(darkTheme = true)
    }

    private fun assertWarningUsesErrorColours(darkTheme: Boolean) {
        val harness = compose.setHostKeyScreen(asking(changed), darkTheme = darkTheme)

        val config = compose.onNodeWithTag(HOSTKEY_WARNING_TAG).fetchSemanticsNode().config
        assertEquals(harness.colors.errorContainer, config[WarningContainerColor])
        assertEquals(harness.colors.onErrorContainer, config[WarningContentColor])
    }

    @Test
    fun `the warning says it in words, not colour alone`() {
        compose.setHostKeyScreen(asking(changed))

        compose.onNode(hasText(CHANGED_TITLE) and hasAnyAncestor(hasTestTag(HOSTKEY_WARNING_TAG)))
            .performScrollTo()
            .assertExists()
    }

    // Shared

    @Test
    fun `fingerprints are in monospace`() {
        compose.setHostKeyScreen(asking(changed))

        for (tag in listOf(HOSTKEY_NEW_FINGERPRINT_TAG, HOSTKEY_APPROVED_FINGERPRINT_TAG)) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily, tag)
        }
    }

    @Test
    fun `the fingerprints are the ones in the request`() {
        compose.setHostKeyScreen(asking(changed))

        compose.onNode(hasTestTag(HOSTKEY_NEW_FINGERPRINT_TAG) and hasText(NEW_FINGERPRINT.sha256), useUnmergedTree = true)
            .assertExists()
        compose.onNode(
            hasTestTag(HOSTKEY_APPROVED_FINGERPRINT_TAG) and hasText(APPROVED_FINGERPRINT.sha256),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `while deciding no choice can be made`() {
        val harness = compose.setHostKeyScreen(asking(firstSeen, deciding = true))
        button(APPROVE).assertIsNotEnabled()
        button(REJECT).assertIsNotEnabled()

        harness.state.value = asking(changed, deciding = true)
        button(CHANGED_REJECT).assertIsNotEnabled()
        button(CHANGED_ACCEPT).assertIsNotEnabled()
    }

    @Test
    fun `dismissing an error sends DismissError`() {
        val harness = compose.setHostKeyScreen(asking(firstSeen).copy(error = HostKeyError.StorageFailed))

        compose.onNodeWithContentDescription(DISMISS).performClick()

        assertEquals(listOf<HostKeyIntent>(HostKeyIntent.DismissError), harness.intents)
    }

    @Test
    fun `every button is at least 48 dp`() {
        val harness = compose.setHostKeyScreen(asking(firstSeen))
        button(APPROVE).assertTouchSizeIsAtLeast(48.dp)
        button(REJECT).assertTouchSizeIsAtLeast(48.dp)

        harness.state.value = asking(changed)
        button(CHANGED_REJECT).assertTouchSizeIsAtLeast(48.dp)
        button(CHANGED_ACCEPT).assertTouchSizeIsAtLeast(48.dp)
        button(CHANGED_ACCEPT).performClick()
        inDialog(CONFIRM_ACCEPT).assertTouchSizeIsAtLeast(48.dp)
        inDialog(CANCEL).assertTouchSizeIsAtLeast(48.dp)
    }

    @Test
    fun `loading shows no request and no choices`() {
        compose.setHostKeyScreen(HostKeyState())

        compose.onNodeWithText(APPROVE).assertDoesNotExist()
        compose.onNodeWithText(REJECT).assertDoesNotExist()
    }
}
