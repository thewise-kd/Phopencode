package io.github.thewisekd.phopencode.feature.preview.ui.ports

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the port picker in one language. Formats take a port number. */
internal data class ExpectedPortsStrings(
    val title: String,
    val refresh: String,
    val loading: String,
    val refreshing: String,
    val listHeading: String,
    val port: (Int) -> String,
    val opencode: String,
    val manualHeading: String,
    val manualLabel: String,
    val manualHint: String,
    val manualOpen: String,
    val manualProblems: Map<ManualPortProblem, String>,
    val emptyTitle: String,
    val emptyMessage: String,
    val emptyRefresh: String,
    val unsupportedTitle: String,
    val unsupportedMessage: String,
    val retry: String,
    val dismiss: String,
    /** Every listing failure the screen words on its own; the rest read [otherError]. */
    val errors: Map<PreviewFailure, String>,
    val otherError: String,
)

/** What the port picker shows, the same in each language; a subclass sets the language and its texts. */
abstract class PortsLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedPortsStrings

    @Test
    fun `the list shows its title, controls and ports`() {
        compose.setPortsScreen(listed)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithText(strings.listHeading).assertIsDisplayed()
        listOf(DEV_PORT, OTHER_PORT).forEach { port ->
            compose.scrollPortsTo(hasText(strings.port(port)))
            compose.onNodeWithText(strings.port(port), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `the opencode port says why it cannot be opened`() {
        compose.setPortsScreen(listed)

        compose.scrollPortsTo(hasText(strings.opencode))
        compose.onNodeWithText(strings.port(OPENCODE_PORT), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(strings.opencode, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the manual field is labelled and explained`() {
        compose.setPortsScreen(listed)

        compose.onNodeWithText(strings.manualHeading).assertIsDisplayed()
        compose.onAllNodesWithText(strings.manualLabel, useUnmergedTree = true).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText(strings.manualHint, useUnmergedTree = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText(strings.manualOpen).assertIsDisplayed()
    }

    @Test
    fun `each manual problem has its own text in place of the hint`() {
        assertEquals(ManualPortProblem.entries.toSet(), strings.manualProblems.keys)
        assertEquals(strings.manualProblems.size, strings.manualProblems.values.toSet().size, "texts must differ")
        val states = PortsStates(compose)
        strings.manualProblems.forEach { (problem, text) ->
            states.show(listed.copy(manualText = "x", manualProblem = problem))
            compose.onAllNodesWithText(text, useUnmergedTree = true).onFirst().assertIsDisplayed()
            compose.onAllNodesWithText(strings.manualHint, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun `loading says so`() {
        compose.setPortsScreen(PortsState())

        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `refreshing says so`() {
        compose.setPortsScreen(listed.copy(refreshing = true))

        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
    }

    @Test
    fun `an empty list says to start the dev server`() {
        compose.setPortsScreen(empty)

        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyRefresh).assertIsDisplayed()
    }

    @Test
    fun `an unsupported server says to enter the port by hand`() {
        compose.setPortsScreen(unsupported)

        compose.onNodeWithText(strings.unsupportedTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.unsupportedMessage).assertIsDisplayed()
    }

    @Test
    fun `every failure reads in the language`() {
        val states = PortsStates(compose)
        val expected = PreviewFailure.entries.associateWith { strings.errors[it] ?: strings.otherError }
        expected.forEach { (failure, text) ->
            states.show(empty.copy(error = failure))
            compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `the listing failures that matter are worded apart`() {
        val listingFailures = setOf(
            PreviewFailure.NotConnected,
            PreviewFailure.ConnectionLost,
            PreviewFailure.TimedOut,
            PreviewFailure.OutputTooLarge,
            PreviewFailure.CommandFailed,
        )
        assertEquals(listingFailures, strings.errors.keys)
        val texts = strings.errors.values + strings.otherError
        assertEquals(texts.size, texts.toSet().size, "texts must differ")
    }

    @Test
    fun `a failure that can be retried offers retry and dismiss`() {
        compose.setPortsScreen(empty.copy(error = PreviewFailure.CommandFailed))

        compose.onNodeWithText(strings.retry).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.dismiss).assertIsDisplayed()
    }

    @Test
    fun `the root keeps its tag in the language`() {
        compose.setPortsScreen(listed)

        compose.onNodeWithTag("placeholder:$route").assertIsDisplayed()
    }
}
