package io.github.thewisekd.phopencode.feature.preview.ui.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPageTest : PageLanguageContract() {
    override val strings = ENGLISH_PAGE_STRINGS
}

internal val ENGLISH_PAGE_STRINGS = ExpectedPageStrings(
    title = "Live preview",
    port = { "Port $it" },
    reload = "Reload",
    opening = "Opening the preview…",
    connectionLost = ExpectedProblem(
        "Connection lost",
        "The connection to the server dropped. Reload once it is back.",
    ),
    nothingListens = { port ->
        ExpectedProblem(
            "Nothing listens on port $port",
            "Start your dev server on the server's 127.0.0.1, or ask opencode to start it, then reload.",
        )
    },
    httpError = { code -> ExpectedProblem("The page did not load", "The dev server answered with HTTP $code.") },
    networkError = ExpectedProblem("The page did not load", "The preview could not reach the dev server."),
    openFailures = mapOf(
        PreviewFailure.NotConnected to ExpectedProblem(
            "Not connected",
            "Go back and connect to the server, then reload.",
        ),
        PreviewFailure.OpencodePort to ExpectedProblem(
            "This is the opencode server's port",
            "It cannot be previewed. Go back and pick another port.",
        ),
        PreviewFailure.BridgeFailed to ExpectedProblem(
            "The preview did not open",
            "The preview could not start on this phone. Reload to try again.",
        ),
    ),
    startFailed = ExpectedProblem(
        "The preview did not open",
        "Something went wrong while opening the preview. Reload to try again.",
    ),
    webViewTooOld = ExpectedProblem(
        "The preview did not open",
        "The system WebView on this phone is too old for the live preview. Update Android System WebView, " +
            "then open the preview again.",
    ),
    externalTitle = "Open outside the app?",
    externalMessage = { url ->
        "The page wants to open an address off the preview. It opens in another app, such as your browser:\n\n$url"
    },
    externalOpen = "Open",
    cancel = "Cancel",
    externalFailed = "No app on this phone can open that address.",
)
