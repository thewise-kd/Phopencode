package io.github.thewisekd.phopencode.feature.connect.ui.progress

import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressAction
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressStep
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishProgressScreenTest : ProgressScreenLanguageContract() {
    override val strings = ExpectedProgressStrings(
        title = "Connection",
        connecting = "Connecting…",
        connected = "Connected",
        failed = "Could not connect",
        steps = mapOf(
            ProgressStep.Ssh to "SSH login",
            ProgressStep.HostApproval to "Host key approval",
            ProgressStep.Server to "opencode server",
            ProgressStep.Api to "API check",
        ),
        done = "Done",
        current = "In progress",
        stepFailed = "Failed",
        pending = "Waiting",
        actions = mapOf(
            ProgressAction.Retry to "Try again",
            ProgressAction.EditServer to "Edit server",
            ProgressAction.ReviewHostKey to "Review host key",
            ProgressAction.EnterServerPassword to "Enter password",
        ),
        cancel = "Cancel",
        passwordTitle = "opencode server password",
        passwordMessage = "An opencode server that this app did not start is running. " +
            "Enter the password it was started with.",
        passwordLabel = "Password",
        passwordSubmit = "Connect",
        noSecondServer = "will not start a second one or stop it",
        errors = mapOf(
            ProgressError.InvalidProfile to "This server's settings are incomplete. Edit the server, then try again.",
            ProgressError.SecretMissing to
                "The saved login for this server is missing. Edit the server to enter it again.",
            ProgressError.SecretUnreadable to
                "The saved login for this server could not be read. Edit the server to enter it again.",
            ProgressError.StorageUnavailable to
                "This phone's secure storage is unavailable. Unlock the phone, then try again.",
            ProgressError.Unreachable to "The server could not be reached. Check the host, the port and your network.",
            ProgressError.Timeout to "The server took too long to answer. Check your network, then try again.",
            ProgressError.Disconnected to "The connection dropped. Try again.",
            ProgressError.AuthenticationFailed to "The server refused the login. Check the user name, password or key.",
            ProgressError.HostKeyRejected to "The server's host key was not approved, so the connection stopped.",
            ProgressError.HostKeyChanged to "The server's host key has changed. Review it before connecting again.",
            ProgressError.OpencodeMissing to "opencode was not found on the server. Install it there, then try again.",
            ProgressError.ServerPasswordUnknown to
                "An opencode server that this app did not start is running. Enter its password to connect.",
            ProgressError.ServerPasswordRejected to "The opencode server refused the password. Enter it again.",
            ProgressError.ServerVersionTooOld to "The opencode on the server is too old. Update it, then try again.",
            ProgressError.ServerUnresponsive to
                "An opencode server is running but not answering. The app will not start a second one or stop it: " +
                "restart it on the server, then try again.",
            ProgressError.ServerUnusable to
                "An opencode server is running but cannot be reached on loopback (127.0.0.1). The app will not " +
                "start a second one or stop it: restart it on the server listening on 127.0.0.1, then try again.",
            ProgressError.ServerDetectionFailed to
                "Could not tell whether opencode is running on the server. Try again.",
            ProgressError.ServerStartFailed to
                "opencode could not be started on the server. Check that it runs there, then try again.",
            ProgressError.ServerLost to "The opencode server stopped while connecting. Try again.",
            ProgressError.ApiFailed to "The opencode server's API returned an error. Try again.",
            ProgressError.Unexpected to "Something unexpected went wrong. Try again.",
        ),
    )
}
