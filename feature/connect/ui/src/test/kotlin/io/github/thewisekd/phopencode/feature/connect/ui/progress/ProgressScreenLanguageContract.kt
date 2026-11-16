package io.github.thewisekd.phopencode.feature.connect.ui.progress

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port [ExpectedProgressStrings.errors] names for [ProgressError.ServerNotOnPort]. */
internal const val NOT_ON_PORT = 4096

/** The texts of the connection screen in one language. */
internal data class ExpectedProgressStrings(
    val title: String,
    val connecting: String,
    val connected: String,
    val failed: String,
    val steps: Map<ProgressStep, String>,
    val done: String,
    val current: String,
    val stepFailed: String,
    val pending: String,
    val actions: Map<ProgressAction, String>,
    val cancel: String,
    val passwordTitle: String,
    val passwordMessage: String,
    val passwordLabel: String,
    val passwordSubmit: String,
    /** What a running server the app will not touch must say, in this language. */
    val noSecondServer: String,
    /** The debug detail's toggle, its two states, and its copy button (P9.38). */
    val details: String,
    val detailsExpanded: String,
    val detailsCollapsed: String,
    val copy: String,
    val errors: Map<ProgressError, String>,
)

/** What the connection screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class ProgressScreenLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedProgressStrings

    @Test
    fun `connecting shows the title, the server name, the status and every step`() {
        compose.setProgressScreen(ProgressState(serverName = SERVER_NAME, step = ProgressStep.Server))

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithText(SERVER_NAME).assertIsDisplayed()
        compose.onNodeWithText(strings.connecting).assertIsDisplayed()
        compose.onNodeWithText(strings.cancel).assertIsDisplayed()
        assertEquals(SHOWN_STEPS.toSet(), strings.steps.keys)
        for (step in SHOWN_STEPS) {
            compose.onNodeWithTag(stepTag(step)).assert(hasText(strings.steps.getValue(step)))
        }
        compose.onNodeWithTag(stepTag(ProgressStep.Ssh)).assert(hasStateDescription(strings.done))
        compose.onNodeWithTag(stepTag(ProgressStep.HostApproval)).assert(hasStateDescription(strings.done))
        compose.onNodeWithTag(stepTag(ProgressStep.Server)).assert(hasStateDescription(strings.current))
        compose.onNodeWithTag(stepTag(ProgressStep.Api)).assert(hasStateDescription(strings.pending))
    }

    @Test
    fun `a failed step and the failure are named`() {
        compose.setProgressScreen(
            ProgressState(serverName = SERVER_NAME, step = ProgressStep.Ssh, error = ProgressError.Timeout),
        )

        compose.onNodeWithText(strings.failed).assertIsDisplayed()
        compose.onNodeWithTag(stepTag(ProgressStep.Ssh)).assert(hasStateDescription(strings.stepFailed))
    }

    @Test
    fun `connected says so`() {
        compose.setProgressScreen(ProgressState(serverName = SERVER_NAME, step = ProgressStep.Connected))

        compose.onNodeWithText(strings.connected).assertIsDisplayed()
    }

    @Test
    fun `every action has its label`() {
        assertEquals(ProgressAction.entries.toSet(), strings.actions.keys)
        compose.setProgressScreen(
            ProgressState(
                serverName = SERVER_NAME,
                error = ProgressError.Unexpected,
                actions = ProgressAction.entries.toSet(),
            ),
        )

        for (label in strings.actions.values) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `every error has its own message`() {
        assertEquals(ProgressError.entries.toSet(), strings.errors.keys, "every ProgressError needs a message")
        assertEquals(strings.errors.size, strings.errors.values.toSet().size, "each error needs its own message")
        val harness = compose.setProgressScreen(ProgressState(serverName = SERVER_NAME))

        for ((error, message) in strings.errors) {
            val port = if (error == ProgressError.ServerNotOnPort) NOT_ON_PORT else null
            harness.state.value =
                ProgressState(serverName = SERVER_NAME, step = ProgressStep.Server, error = error, errorPort = port)

            compose.onNodeWithTag(PROGRESS_ERROR_TAG).assert(hasText(message))
        }
    }

    @Test
    fun `a running server the app will not touch says it will not start a second one or stop it`() {
        for (error in listOf(ProgressError.ServerUnresponsive, ProgressError.ServerUnusable)) {
            assertTrue(
                strings.noSecondServer in strings.errors.getValue(error),
                "$error must say the app will not start a second server or stop it",
            )
        }
    }

    @Test
    fun `no server on the set port names that port`() {
        compose.setProgressScreen(
            ProgressState(
                serverName = SERVER_NAME,
                step = ProgressStep.Server,
                error = ProgressError.ServerNotOnPort,
                actions = setOf(ProgressAction.EditServer, ProgressAction.Retry),
                errorPort = 49374,
            ),
        )

        val expected = strings.errors.getValue(ProgressError.ServerNotOnPort).replace("$NOT_ON_PORT", "49374")
        compose.onNodeWithTag(PROGRESS_ERROR_TAG).assert(hasText(expected))
        compose.onNodeWithText(strings.actions.getValue(ProgressAction.EditServer)).assertIsDisplayed()
    }

    @Test
    fun `the server password prompt is in this language`() {
        compose.setProgressScreen(
            ProgressState(
                serverName = SERVER_NAME,
                step = ProgressStep.Server,
                error = ProgressError.ServerPasswordUnknown,
                askingServerPassword = true,
            ),
        )

        compose.onNode(hasText(strings.passwordTitle) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(strings.passwordMessage) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(strings.passwordLabel) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(strings.passwordSubmit) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(strings.cancel) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun `the debug detail is collapsed under the error until opened, then shown and copied as is`() {
        compose.setProgressScreen(failedWith(DEBUG_DETAIL))

        // The content scrolls; on a small screen the section may start below the fold.
        compose.onNodeWithTag(PROGRESS_ERROR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasText(strings.details))
            .assert(hasClickAction())
            .assert(hasStateDescription(strings.detailsCollapsed))
            .assertTouchSizeIsAtLeast()
        compose.onNodeWithTag(DETAILS_TEXT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DETAILS_COPY_TAG).assertDoesNotExist()

        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).performClick()

        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).assert(hasStateDescription(strings.detailsExpanded))
        compose.onNodeWithTag(DETAILS_TEXT_TAG).performScrollTo().assertIsDisplayed().assert(hasText(DEBUG_DETAIL))
        compose.onNodeWithTag(DETAILS_COPY_TAG)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasText(strings.copy))
            .assertTouchSizeIsAtLeast()
            .performClick()
        compose.waitForIdle()

        assertEquals(DEBUG_DETAIL, clipboardText())

        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).assert(hasStateDescription(strings.detailsCollapsed))
        compose.onNodeWithTag(DETAILS_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `without a debug detail there is no details section and no copy button`() {
        compose.setProgressScreen(failedWith(detail = null))

        compose.onNodeWithTag(PROGRESS_ERROR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(DETAILS_COPY_TAG).assertDoesNotExist()
        compose.onNodeWithText(strings.details).assertDoesNotExist()
        compose.onNodeWithText(strings.copy).assertDoesNotExist()
    }

    @Test
    fun `a new failure's detail replaces the old one and starts collapsed`() {
        val harness = compose.setProgressScreen(failedWith(DEBUG_DETAIL))
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).performScrollTo().performClick()

        harness.state.value = failedWith(OTHER_DETAIL)

        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).assert(hasStateDescription(strings.detailsCollapsed))
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(DETAILS_TEXT_TAG).assert(hasText(OTHER_DETAIL))

        harness.state.value = ProgressState(serverName = SERVER_NAME, step = ProgressStep.Ssh)
        compose.onNodeWithTag(DETAILS_TOGGLE_TAG).assertDoesNotExist()
    }

    private fun failedWith(detail: String?) = ProgressState(
        serverName = SERVER_NAME,
        step = ProgressStep.Ssh,
        error = ProgressError.Unreachable,
        actions = setOf(ProgressAction.EditServer, ProgressAction.Retry),
        debugDetail = detail,
    )

    private fun clipboardText(): String? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `the root keeps the placeholder tag`() {
        compose.setProgressScreen(ProgressState(serverName = SERVER_NAME))

        compose.onNodeWithTag("placeholder:$ROUTE").assertExists()
    }
}
