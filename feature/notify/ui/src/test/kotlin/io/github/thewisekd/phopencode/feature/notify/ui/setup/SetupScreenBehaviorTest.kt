package io.github.thewisekd.phopencode.feature.notify.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServer
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServerProblem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the setup sends and opens, and what it keeps hidden. In English; the words are in the language tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class SetupScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tagged(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag).performScrollTo()

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** Shows a setup whose state the test changes; returns the function that changes it. */
    private fun showChanging(initial: SetupState, onIntent: (SetupIntent) -> Unit = {}): (SetupState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides FakeUriHandler()) {
                    SetupScreen(NotifyRoute.Setup, state, onIntent = onIntent)
                }
            }
        }
        return { next ->
            state = next
            compose.waitForIdle()
        }
    }

    /** Asserts no node, merged or not, shows or speaks the topic or the whole link. */
    private fun assertTopicHidden() {
        for (merged in listOf(true, false)) {
            val nodes = compose.onAllNodes(anyNode, useUnmergedTree = !merged).fetchSemanticsNodes()
            for (node in nodes) {
                for (said in spokenBy(node)) {
                    assertTrue(TOPIC_TEXT !in said, "the topic is on the screen: a node says \"$said\"")
                }
            }
        }
    }

    // The ntfy app and the link

    @Test
    fun `the topic never reaches the screen, whatever the state`() {
        val update = showChanging(loadedState)
        val states = listOf(
            loadedState,
            loadedState.copy(testSent = true, status = NotifyServiceStatus.Stopped),
            loadedState.copy(operation = SetupStep.Install),
            loadedState.copy(error = SetupError(SetupStep.SendTest, NotifyFailure.NtfyFailed)),
            loadedState.copy(status = NotifyServiceStatus.NotInstalled, serverText = "https://ntfy.example.com"),
        )
        for (state in states) {
            update(state)
            assertTopicHidden()
        }
    }

    @Test
    fun `subscribing opens the link with the system`() {
        val harness = compose.showSetup(loadedState)

        tagged(SETUP_OPEN_TAG).performClick()

        assertEquals(listOf(link), harness.uris.opened)
        compose.onNodeWithTag(SETUP_APP_MISSING_TAG).assertDoesNotExist()
        assertTrue(harness.intents.isEmpty())
    }

    @Test
    fun `without the ntfy app, the screen offers the app's page, and a later success clears it`() {
        val uris = FakeUriHandler(missing = true)
        compose.showSetup(loadedState, uris)

        tagged(SETUP_OPEN_TAG).performClick()
        tagged(SETUP_APP_MISSING_TAG).assertIsDisplayed()
        assertTopicHidden()

        uris.missing = false
        tagged(SETUP_GET_APP_TAG).performClick()
        assertEquals(listOf(EXPECTED_APP_PAGE), uris.opened)

        tagged(SETUP_OPEN_TAG).performClick()
        compose.onNodeWithTag(SETUP_APP_MISSING_TAG).assertDoesNotExist()
        assertEquals(listOf(EXPECTED_APP_PAGE, link), uris.opened)
    }

    @Test
    fun `the missing app note can be dismissed`() {
        compose.showSetup(loadedState, FakeUriHandler(missing = true))

        tagged(SETUP_OPEN_TAG).performClick()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        compose.onNodeWithTag(SETUP_APP_MISSING_TAG).assertDoesNotExist()
    }

    @Test
    fun `there is nothing to open before the link is loaded`() {
        compose.showSetup(loadedState.copy(subscriptionLink = null, error = SetupError(SetupStep.Load, NotifyFailure.StorageFailed)))

        tagged(SETUP_OPEN_TAG).assertIsNotEnabled()
    }

    // The service

    @Test
    fun `install, test and refresh send their intents`() {
        val harness = compose.showSetup(loadedState.copy(status = NotifyServiceStatus.NotInstalled))

        tagged(SETUP_INSTALL_TAG).performClick()
        tagged(SETUP_TEST_TAG).performClick()
        tagged(SETUP_REFRESH_TAG).performClick()

        assertEquals(listOf(SetupIntent.Install, SetupIntent.SendTest, SetupIntent.RefreshStatus), harness.intents)
    }

    @Test
    fun `remove is offered only for an installed service`() {
        compose.showSetup(loadedState.copy(status = NotifyServiceStatus.NotInstalled))

        compose.onNodeWithTag(SETUP_REMOVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `removing sends nothing until confirmed`() {
        val harness = compose.showSetup(loadedState.copy(status = NotifyServiceStatus.Stopped))

        tagged(SETUP_REMOVE_TAG).performClick()
        assertTrue(harness.intents.isEmpty())
        inDialog("Remove").performClick()

        assertEquals(listOf<SetupIntent>(SetupIntent.Remove), harness.intents)
        compose.onNodeWithTag(SETUP_REMOVE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `cancelling the removal sends nothing`() {
        val harness = compose.showSetup(loadedState)

        tagged(SETUP_REMOVE_TAG).performClick()
        inDialog("Cancel").performClick()

        assertTrue(harness.intents.isEmpty())
        compose.onNodeWithTag(SETUP_REMOVE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `while a step runs, no other can start`() {
        compose.showSetup(loadedState.copy(operation = SetupStep.SendTest))

        tagged(SETUP_PROGRESS_TAG).assertIsDisplayed()
        listOf(SETUP_INSTALL_TAG, SETUP_REMOVE_TAG, SETUP_TEST_TAG, SETUP_REFRESH_TAG, SETUP_SERVER_SAVE_TAG, SETUP_SERVER_RESET_TAG)
            .forEach { tagged(it).assertIsNotEnabled() }
        tagged(SETUP_OPEN_TAG).assertIsEnabled()
    }

    @Test
    fun `while loading, only the progress shows`() {
        compose.showSetup(SetupState())

        compose.onNodeWithTag(SETUP_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SETUP_INSTALL_TAG).assertDoesNotExist()
    }

    @Test
    fun `a taken test is marked`() {
        compose.showSetup(loadedState.copy(testSent = true))

        tagged(SETUP_TEST_SENT_TAG).assertIsDisplayed()
    }

    // The ntfy server

    @Test
    fun `editing, saving and resetting the server send their intents`() {
        val custom = NtfyServer.parse("https://ntfy.example.com")!!
        val harness = compose.showSetup(
            loadedState.copy(server = custom, serverText = "https://ntfy.example.com/x"),
        )

        tagged(SETUP_SERVER_FIELD_TAG).performTextReplacement("https://ntfy.example.org")
        tagged(SETUP_SERVER_SAVE_TAG).performClick()
        tagged(SETUP_SERVER_RESET_TAG).performClick()

        assertEquals(
            listOf("EditServer", "SaveServer", "ResetServer"),
            harness.intents.map { it::class.simpleName },
        )
        assertEquals("https://ntfy.example.org", (harness.intents.first() as SetupIntent.EditServer).text)
    }

    @Test
    fun `an unchanged server cannot be saved, and the default cannot be reset`() {
        compose.showSetup(loadedState)

        tagged(SETUP_SERVER_SAVE_TAG).assertIsNotEnabled()
        tagged(SETUP_SERVER_RESET_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a broken server cannot be saved`() {
        compose.showSetup(loadedState.copy(serverText = "http://x", serverProblem = NtfyServerProblem.NotHttps))

        tagged(SETUP_SERVER_SAVE_TAG).assertIsNotEnabled()
    }

    // Errors and closing

    @Test
    fun `an error can be dismissed and its step retried`() {
        val harness = compose.showSetup(loadedState.copy(error = SetupError(SetupStep.Install, NotifyFailure.Unreachable)))

        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Dismiss error").performScrollTo().performClick()

        assertEquals(listOf(SetupIntent.Install, SetupIntent.DismissError), harness.intents)
    }

    @Test
    fun `each retry repeats its own step`() {
        val retries = mapOf(
            SetupStep.SaveServer to SetupIntent.SaveServer,
            SetupStep.CheckStatus to SetupIntent.RefreshStatus,
            SetupStep.Remove to SetupIntent.Remove,
            SetupStep.SendTest to SetupIntent.SendTest,
        )
        val sent = mutableListOf<SetupIntent>()
        val update = showChanging(loadedState) { sent += it }
        for ((step, intent) in retries) {
            update(loadedState.copy(error = SetupError(step, NotifyFailure.Unreachable)))
            compose.onNodeWithText("Try again").performScrollTo().performClick()
            assertEquals(intent, sent.last())
        }
    }

    @Test
    fun `nothing to retry without a connection or for a failed load`() {
        val update = showChanging(loadedState)
        for (error in listOf(
            SetupError(SetupStep.Install, NotifyFailure.NotConnected),
            SetupError(SetupStep.Load, NotifyFailure.StorageFailed),
        )) {
            update(loadedState.copy(error = error))
            compose.onNodeWithText("Try again").assertDoesNotExist()
        }
    }

    @Test
    fun `back sends Close`() {
        val harness = compose.showSetup(loadedState)

        compose.onNodeWithContentDescription("Go back").performClick()

        assertEquals(listOf<SetupIntent>(SetupIntent.Close), harness.intents)
    }

    @Test
    fun `every button is at least a touch target`() {
        compose.showSetup(loadedState.copy(serverText = "https://ntfy.example.com"))

        listOf(
            SETUP_OPEN_TAG,
            SETUP_REFRESH_TAG,
            SETUP_INSTALL_TAG,
            SETUP_REMOVE_TAG,
            SETUP_TEST_TAG,
            SETUP_SERVER_SAVE_TAG,
            SETUP_SERVER_RESET_TAG,
        ).forEach { tagged(it).assertTouchSizeIsAtLeast() }
    }

    @Test
    fun `the root keeps the route's tag`() {
        compose.showSetup(loadedState)

        compose.onNodeWithTag("placeholder:${NotifyRoute.Setup}").assertIsDisplayed()
    }
}
