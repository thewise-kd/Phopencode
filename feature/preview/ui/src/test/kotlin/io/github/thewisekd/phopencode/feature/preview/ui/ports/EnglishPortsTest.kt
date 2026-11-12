package io.github.thewisekd.phopencode.feature.preview.ui.ports

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ManualPortProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPortsTest : PortsLanguageContract() {
    override val strings = ENGLISH_PORTS_STRINGS
}

internal val ENGLISH_PORTS_STRINGS = ExpectedPortsStrings(
    title = "Live preview",
    refresh = "Refresh ports",
    loading = "Looking for listening ports…",
    refreshing = "Refreshing ports…",
    listHeading = "Listening on the server",
    port = { "Port $it" },
    opencode = "The opencode server. It cannot be previewed.",
    manualHeading = "Enter a port",
    manualLabel = "Port",
    manualHint = "A port from 1 to 65535 on the server's 127.0.0.1",
    manualOpen = "Open",
    manualProblems = mapOf(
        ManualPortProblem.Empty to "Enter a port number.",
        ManualPortProblem.NotANumber to "Use digits only.",
        ManualPortProblem.OutOfRange to "Use a port from 1 to 65535.",
        ManualPortProblem.OpencodePort to "This is the opencode server's port. It cannot be previewed.",
    ),
    emptyTitle = "No dev server found",
    emptyMessage = "Nothing listens on the server's 127.0.0.1 yet. Start your dev server there, " +
        "or ask opencode to start it, then refresh.",
    emptyRefresh = "Refresh",
    unsupportedTitle = "Ports cannot be listed",
    unsupportedMessage = "This server offers no way to list its ports. Enter your dev server's port below.",
    retry = "Try again",
    dismiss = "Dismiss error",
    errors = mapOf(
        PreviewFailure.NotConnected to "Not connected to the server. Go back and connect again.",
        PreviewFailure.ConnectionLost to "The connection to the server was lost. Try again once it is back.",
        PreviewFailure.TimedOut to "Listing the ports took too long.",
        PreviewFailure.OutputTooLarge to "The server listed more than the app reads.",
        PreviewFailure.CommandFailed to "The server could not list its ports.",
    ),
    otherError = "The ports could not be listed.",
)
