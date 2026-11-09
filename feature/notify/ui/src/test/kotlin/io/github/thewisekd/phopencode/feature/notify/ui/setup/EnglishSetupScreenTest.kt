package io.github.thewisekd.phopencode.feature.notify.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.navigation.NotifyRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfyServerProblem
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupError
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The setup in English: the explanation, every status, step, problem and failure in words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shown(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    /** Shows a setup whose state the test changes, and the link handler it uses. */
    private fun showChanging(initial: SetupState): (SetupState) -> Unit {
        var state by mutableStateOf(initial)
        val uris = FakeUriHandler()
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                CompositionLocalProvider(LocalUriHandler provides uris) {
                    SetupScreen(NotifyRoute.Setup, state, onIntent = {})
                }
            }
        }
        return { next ->
            state = next
            compose.waitForIdle()
        }
    }

    @Test
    fun `every section is in English`() {
        compose.showSetup(loadedState)

        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").assertIsDisplayed()
        shown("The ntfy app")
        shown(
            "Notifications reach this phone through ntfy, a free app. Install ntfy on this phone, then " +
                "subscribe to your private topic below.",
        )
        shown(
            "Anyone who knows the topic can read the notifications, so it stays hidden here. They carry " +
                "only a short fixed notice, never your messages or code.",
        )
        shown("ntfy://ntfy.sh/••••••••")
        shown("Subscribe in ntfy")
        shown("Service on the server")
        shown(
            "A small service on the server of this connection watches opencode and sends the " +
                "notifications. It needs python3 3.9 or later.",
        )
        shown("Running")
        shown("Reinstall")
        shown("Remove")
        shown("Test notification")
        shown("Sends one notification from the server to your topic, to try the whole way to this phone.")
        shown("Send a test")
        shown("ntfy server")
        shown("ntfy.sh is used unless you run your own ntfy server.")
        shown("Save")
        shown("Use ntfy.sh")
    }

    @Test
    fun `the link reads as a hidden topic`() {
        compose.showSetup(loadedState)

        compose.onNodeWithContentDescription("Subscription link on ntfy.sh, topic hidden")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `each status is in English`() {
        val update = showChanging(loadedState)
        val labels = mapOf(
            NotifyServiceStatus.NotInstalled to "Not installed",
            NotifyServiceStatus.Stopped to "Stopped",
            NotifyServiceStatus.Running to "Running",
            null to "Unknown",
        )
        for ((status, label) in labels) {
            update(loadedState.copy(status = status))
            compose.onNodeWithTag(SETUP_STATUS_TAG).performScrollTo().assertTextContains(label)
        }
        compose.onNodeWithContentDescription("Check the status again").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the install button says what it does`() {
        val update = showChanging(loadedState)
        val labels = mapOf(
            NotifyServiceStatus.NotInstalled to "Install",
            null to "Install",
            NotifyServiceStatus.Stopped to "Install and start",
            NotifyServiceStatus.Running to "Reinstall",
        )
        for ((status, label) in labels) {
            update(loadedState.copy(status = status))
            compose.onNodeWithTag(SETUP_INSTALL_TAG).performScrollTo().assertTextContains(label)
        }
    }

    @Test
    fun `loading is a still message`() {
        compose.showSetup(SetupState())

        compose.onNodeWithTag(SETUP_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading the notification settings…").assertIsDisplayed()
    }

    @Test
    fun `each running step says what it does`() {
        val update = showChanging(loadedState)
        val messages = mapOf(
            SetupStep.SaveServer to "Saving the ntfy server…",
            SetupStep.CheckStatus to "Checking the service…",
            SetupStep.Install to "Installing the service…",
            SetupStep.Remove to "Removing the service…",
            SetupStep.SendTest to "Sending a test notification…",
        )
        for ((step, message) in messages) {
            update(loadedState.copy(operation = step))
            compose.onNodeWithContentDescription(message).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `each server problem is shown under the field`() {
        val update = showChanging(loadedState)
        val messages = mapOf(
            NtfyServerProblem.Blank to "Enter the server's address.",
            NtfyServerProblem.NotHttps to "The address must start with https://.",
            NtfyServerProblem.Invalid to
                "Enter only the address, like https://ntfy.example.com, without a path or a password.",
        )
        for ((problem, message) in messages) {
            update(loadedState.copy(serverText = "x", serverProblem = problem))
            shown(message)
        }
    }

    @Test
    fun `an installed service keeps its server until installed again`() {
        compose.showSetup(loadedState.copy(serverText = "https://ntfy.example.com"))

        shown("An installed service keeps its old server until you install it again.")
    }

    @Test
    fun `each failure names the step and the reason`() {
        val update = showChanging(loadedState)
        val steps = mapOf(
            SetupStep.Load to "The notification settings could not be loaded.",
            SetupStep.SaveServer to "The ntfy server could not be saved.",
            SetupStep.CheckStatus to "The service's status could not be checked.",
            SetupStep.Install to "The service could not be installed.",
            SetupStep.Remove to "The service could not be removed.",
            SetupStep.SendTest to "The test notification could not be sent.",
        )
        for ((step, message) in steps) {
            update(loadedState.copy(error = SetupError(step, NotifyFailure.Unknown)))
            shown("$message Something went wrong.")
        }
        val reasons = mapOf(
            NotifyFailure.NotConnected to "No server is connected. Connect to a server, then try again.",
            NotifyFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
            NotifyFailure.Unauthorized to "The server refused the credentials.",
            NotifyFailure.NotFound to "The server could not find what was asked for.",
            NotifyFailure.Rejected to "The server refused the request.",
            NotifyFailure.ServerFailed to "The server ran into an error.",
            NotifyFailure.PythonMissing to
                "The server has no python3, which the notification service needs. Nothing was installed.",
            NotifyFailure.PythonTooOld to
                "The server's python3 is older than 3.9, which the notification service needs. Nothing was installed.",
            NotifyFailure.InstallFailed to "A step on the server failed.",
            NotifyFailure.StorageFailed to "The ntfy settings could not be read from or saved on this phone.",
            NotifyFailure.NtfyFailed to "The ntfy server did not take the test notification. Check the ntfy server.",
            NotifyFailure.Unknown to "Something went wrong.",
        )
        for ((reason, message) in reasons) {
            update(loadedState.copy(error = SetupError(SetupStep.Install, reason)))
            shown("The service could not be installed. $message")
        }
    }

    @Test
    fun `a taken test says so`() {
        compose.showSetup(loadedState.copy(testSent = true))

        shown("The ntfy server took the test notification. It should show up in the ntfy app shortly.")
    }

    @Test
    fun `a missing ntfy app is explained, with a way to get it`() {
        compose.showSetup(loadedState, FakeUriHandler(missing = true))

        compose.onNodeWithText("Subscribe in ntfy").performScrollTo().performClick()

        shown(
            "The ntfy app is not installed, so the link could not open. Install ntfy, then tap " +
                "Subscribe in ntfy again.",
        )
        shown("Get ntfy")
    }

    @Test
    fun `removing asks first, in English`() {
        compose.showSetup(loadedState)

        compose.onNodeWithTag(SETUP_REMOVE_TAG).performScrollTo().performClick()

        compose.onNodeWithText("Remove the service?").assertIsDisplayed()
        compose.onNodeWithText(
            "The service and its settings are removed from the server, and notifications stop. " +
                "You can install it again at any time.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
