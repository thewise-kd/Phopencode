package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/** One problem the page shows in place of the WebView: its title and its message. */
internal data class ExpectedProblem(val title: String, val message: String)

/** The texts of the preview page in one language. Formats take a port number or a status code. */
internal data class ExpectedPageStrings(
    val title: String,
    val port: (Int) -> String,
    val reload: String,
    val opening: String,
    val connectionLost: ExpectedProblem,
    val nothingListens: (Int) -> ExpectedProblem,
    val httpError: (Int) -> ExpectedProblem,
    val networkError: ExpectedProblem,
    /** Every failure to open that the screen words on its own; the rest read [startFailed]. */
    val openFailures: Map<PreviewFailure, ExpectedProblem>,
    val startFailed: ExpectedProblem,
    val externalTitle: String,
    /** The confirmation's text around the address, which it shows in full. */
    val externalMessage: (String) -> String,
    val externalOpen: String,
    val cancel: String,
    val externalFailed: String,
)

/** What the preview page shows, the same in each language; a subclass sets the language and its texts. */
abstract class PageLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedPageStrings

    private fun assertProblem(problem: ExpectedProblem) {
        compose.onNodeWithText(problem.title).assertIsDisplayed()
        compose.onNodeWithText(problem.message).assertIsDisplayed()
        compose.onNodeWithText(strings.reload).assertIsDisplayed()
    }

    @Test
    fun `the top bar names the preview, its port and the reload button`() {
        compose.setPageScreen(opening)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(strings.port(PAGE_PORT)).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.reload).assertIsDisplayed()
    }

    @Test
    fun `opening says so`() {
        compose.setPageScreen(opening)

        compose.onNodeWithTag(PageTags.OPENING).assertIsDisplayed()
        compose.onNodeWithText(strings.opening).assertIsDisplayed()
    }

    @Test
    fun `a lost connection says so, with reload`() {
        val states = PageStates(compose)
        states.show(connectionLost)
        assertProblem(strings.connectionLost)

        states.show(failedToOpen(PreviewFailure.ConnectionLost))
        assertProblem(strings.connectionLost)
    }

    @Test
    fun `nothing listening names the port, with reload`() {
        val states = PageStates(compose)
        states.show(nothingListens)
        assertProblem(strings.nothingListens(PAGE_PORT))

        states.show(failedToOpen(PreviewFailure.NothingListens))
        assertProblem(strings.nothingListens(PAGE_PORT))
    }

    @Test
    fun `a load error names the status or the network, with reload`() {
        val states = PageStates(compose)
        states.show(httpError)
        assertProblem(strings.httpError(502))

        states.show(networkError)
        assertProblem(strings.networkError)
    }

    @Test
    fun `each failure to open has its words, with reload`() {
        val states = PageStates(compose)
        strings.openFailures.forEach { (failure, problem) ->
            states.show(failedToOpen(failure))
            assertProblem(problem)
        }
        PreviewFailure.entries
            .filter { it !in strings.openFailures && it != PreviewFailure.ConnectionLost && it != PreviewFailure.NothingListens }
            .forEach { failure ->
                states.show(failedToOpen(failure))
                assertProblem(strings.startFailed)
            }
    }

    @Test
    fun `an external address is shown in full before it opens`() {
        compose.setPageScreen(askingExternal)

        compose.onNodeWithText(strings.externalTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.externalMessage(EXTERNAL_URL)).assertIsDisplayed()
        compose.onNodeWithText(strings.externalOpen).assertIsDisplayed()
        compose.onNodeWithText(strings.cancel).assertIsDisplayed()
    }

    @Test
    fun `an address no app can open says so`() {
        // Robolectric starts any activity unless asked to check that one resolves; none does here.
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        compose.setPageScreen(askingExternal)

        compose.onNodeWithText(strings.externalOpen).performClick()

        compose.onNodeWithText(strings.externalFailed).assertIsDisplayed()
    }
}
