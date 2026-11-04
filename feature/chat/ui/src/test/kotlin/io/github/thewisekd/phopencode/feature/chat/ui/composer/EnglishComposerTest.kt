package io.github.thewisekd.phopencode.feature.chat.ui.composer

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishComposerTest : ComposerLanguageContract() {
    override val strings = ExpectedComposerStrings(
        fieldLabel = "Message",
        send = "Send",
        stop = "Stop",
        sending = "Sending the message…",
        stopping = "Stopping the work…",
        deliveryLabel = "Send while it works:",
        steer = "Steer now",
        queue = "Queue",
        failed = mapOf(
            ComposerAction.Send to "The message could not be sent.",
            ComposerAction.Stop to "The work could not be stopped.",
        ),
        reasons = mapOf(
            ChatFailure.NotConnected to "There is no connection to the server. Go back and connect again.",
            ChatFailure.Unreachable to "The server could not be reached. Check the connection and try again.",
            ChatFailure.Unauthorized to "The server refused the credentials.",
            ChatFailure.NotFound to "The session no longer exists; it may have been deleted on another device.",
            ChatFailure.Conflict to "The session is busy or was changed meanwhile.",
            ChatFailure.Rejected to "The server refused the request.",
            ChatFailure.ServerFailed to "The server ran into an error.",
            ChatFailure.Unknown to "An unexpected error occurred.",
        ),
    )
}
