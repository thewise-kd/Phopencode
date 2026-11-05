package io.github.thewisekd.phopencode.feature.chat.ui.status

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.model.StructuredError
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test

/** The texts of the status in one language. */
internal data class ExpectedStatusStrings(
    val loading: String,
    val running: String,
    val retrying: String,
    val retryingAttempt: (Int) -> String,
    val nextTry: (String) -> String,
    val failed: (String) -> String,
    val failedGeneric: String,
    val disconnected: String,
    val reconnect: String,
    val close: String,
    val notConnectedTitle: String,
    val notConnectedMessage: String,
    val notFoundTitle: String,
    val notFoundMessage: String,
    val otherServerTitle: String,
    val otherServerMessage: String,
    val unavailableTitle: String,
    val unavailableReasons: Map<ChatFailure, String>,
)

/** What the status says, the same in each language; a subclass sets the language and its texts. */
abstract class StatusLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedStatusStrings

    /** The status a test draws once and then changes; content can be set only once per test. */
    private var shown: StatusHarness? = null

    private fun show(status: ChatStatus) {
        val current = shown
        if (current == null) shown = compose.setStatus(StatusState(status)) else current.state.value = StatusState(status)
        compose.waitForIdle()
    }

    /** Scrolled into view first: at API 35+ the insets push the content down. */
    private fun shows(text: String): SemanticsNodeInteraction =
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    private fun lacks(text: String, substring: Boolean = true) {
        compose.onNodeWithText(text, substring = substring).assertDoesNotExist()
    }

    @Test
    fun `a dropped connection says so and offers to reconnect`() {
        show(ChatStatus.Disconnected)

        shows(strings.disconnected)
        shows(strings.reconnect)
    }

    @Test
    fun `running work is a still label`() {
        show(ChatStatus.Running)

        shows(strings.running)
    }

    @Test
    fun `a retry says its attempt, when the next try is, and the provider's text`() {
        show(ChatStatus.Retrying(attempt = 2, nextAttemptAt = NEXT_TRY, message = PROVIDER_TEXT))

        shows(strings.retryingAttempt(2))
        shows(strings.nextTry(timeOfDay(NEXT_TRY)))
        shows(PROVIDER_TEXT)
    }

    @Test
    fun `a retry without a counter or a time says only that it retries`() {
        show(ChatStatus.Retrying(attempt = null, nextAttemptAt = null))

        shows(strings.retrying)
        lacks(strings.nextTry(""))
    }

    @Test
    fun `a failure shows the server's error, its type, or a plain sentence`() {
        show(ChatStatus.Failed(StructuredError(type = "provider.transport", message = ERROR_TEXT)))
        shows(strings.failed(ERROR_TEXT))

        show(ChatStatus.Failed(StructuredError(type = "provider.transport", message = " ")))
        shows(strings.failed("provider.transport"))

        show(ChatStatus.Failed(null))
        shows(strings.failedGeneric)
    }

    @Test
    fun `loading says it loads the session`() {
        show(ChatStatus.Loading)

        shows(strings.loading)
    }

    @Test
    fun `no connection says so, says to go back to the server list, and offers to close`() {
        show(ChatStatus.NotConnected)

        shows(strings.notConnectedTitle)
        shows(strings.notConnectedMessage)
        shows(strings.close)
        lacks(strings.reconnect, substring = false)
    }

    @Test
    fun `a missing session says the server does not have it`() {
        show(ChatStatus.NotFound)

        shows(strings.notFoundTitle)
        shows(strings.notFoundMessage)
        shows(strings.close)
    }

    @Test
    fun `a session of another server says it belongs to another server`() {
        show(ChatStatus.OtherServer)

        shows(strings.otherServerTitle)
        shows(strings.otherServerMessage)
        shows(strings.close)
    }

    @Test
    fun `an unavailable session says why`() {
        ChatFailure.entries.forEach { failure ->
            show(ChatStatus.Unavailable(failure))

            shows(strings.unavailableTitle)
            shows(strings.unavailableReasons.getValue(failure))
            shows(strings.close)
        }
    }
}
