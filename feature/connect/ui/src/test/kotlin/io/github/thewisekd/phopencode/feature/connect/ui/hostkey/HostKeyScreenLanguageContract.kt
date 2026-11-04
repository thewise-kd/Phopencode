package io.github.thewisekd.phopencode.feature.connect.ui.hostkey

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the host key screen in one language. Formats take the key type. */
internal data class ExpectedHostKeyStrings(
    val title: String,
    val loading: String,
    val none: String,
    val deciding: String,
    val type: (String) -> String,
    val fingerprintLabel: String,
    val firstTitle: String,
    val firstMessage: String,
    val approve: String,
    val reject: String,
    val changedTitle: String,
    val changedMessage: String,
    val previousLabel: String,
    val newLabel: String,
    val changedReject: String,
    val changedAccept: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmAccept: String,
    val errors: Map<HostKeyError, String>,
)

/** What the host key screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class HostKeyScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedHostKeyStrings

    private fun shows(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `a first seen key shows its type, fingerprint, explanation and both choices`() {
        compose.setHostKeyScreen(asking(firstSeen))

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        shows(strings.firstTitle)
        shows(strings.firstMessage)
        shows(strings.type(KEY_TYPE))
        shows(strings.fingerprintLabel)
        shows(NEW_FINGERPRINT.sha256)
        shows(strings.approve)
        shows(strings.reject)
        compose.onNodeWithText(strings.changedTitle).assertDoesNotExist()
    }

    @Test
    fun `a changed key warns and shows both fingerprints`() {
        compose.setHostKeyScreen(asking(changed))

        shows(strings.changedTitle)
        shows(strings.changedMessage)
        shows(strings.type(KEY_TYPE))
        shows(strings.previousLabel)
        shows(APPROVED_FINGERPRINT.sha256)
        shows(strings.newLabel)
        shows(NEW_FINGERPRINT.sha256)
        shows(strings.changedReject)
        shows(strings.changedAccept)
        compose.onNodeWithText(strings.approve).assertDoesNotExist()
    }

    @Test
    fun `accepting a changed key asks again`() {
        compose.setHostKeyScreen(asking(changed))

        compose.onNodeWithText(strings.changedAccept).performScrollTo().performClick()

        compose.onNodeWithText(strings.confirmTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.confirmMessage).assertIsDisplayed()
        compose.onNode(hasText(strings.confirmAccept) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun `loading says so`() {
        compose.setHostKeyScreen(HostKeyState())

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `deciding says so`() {
        compose.setHostKeyScreen(asking(firstSeen, deciding = true))

        compose.onNodeWithContentDescription(strings.deciding).assertExists()
    }

    @Test
    fun `nothing to decide says so`() {
        compose.setHostKeyScreen(HostKeyState(loading = false))

        compose.onNodeWithText(strings.none).assertIsDisplayed()
    }

    @Test
    fun `every error has its own message`() {
        assertEquals(HostKeyError.entries.toSet(), strings.errors.keys)
        assertEquals(strings.errors.size, strings.errors.values.toSet().size, "messages must differ")
        val harness = compose.setHostKeyScreen(HostKeyState(loading = false))

        for ((error, message) in strings.errors) {
            harness.state.value = HostKeyState(loading = false, error = error)

            compose.onNodeWithText(message).assertIsDisplayed()
        }
    }

    @Test
    fun `an error shows above the request it concerns`() {
        compose.setHostKeyScreen(asking(changed).copy(error = HostKeyError.StorageFailed))

        compose.onNodeWithText(strings.errors.getValue(HostKeyError.StorageFailed)).assertIsDisplayed()
        shows(strings.changedTitle)
    }

    @Test
    fun `the root keeps the placeholder tag`() {
        compose.setHostKeyScreen(HostKeyState())

        compose.onNodeWithTag("placeholder:$TEST_ROUTE").assertExists()
    }
}
