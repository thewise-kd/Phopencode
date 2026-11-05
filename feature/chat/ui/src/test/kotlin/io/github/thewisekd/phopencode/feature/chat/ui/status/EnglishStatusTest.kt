package io.github.thewisekd.phopencode.feature.chat.ui.status

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishStatusTest : StatusLanguageContract() {
    override val strings = ExpectedStatusStrings(
        loading = "Loading the session…",
        running = "Working…",
        retrying = "Retrying",
        retryingAttempt = { "Retrying, attempt $it" },
        nextTry = { "Next try at $it" },
        failed = { "The last run failed: $it" },
        failedGeneric = "The last run failed.",
        disconnected = "The connection to the server dropped. What shows may be out of date.",
        reconnect = "Reconnect",
        close = "Close",
        notConnectedTitle = "Not connected",
        notConnectedMessage = "There is no connection to the server, so the session cannot be loaded. " +
            "Go back to the server list to connect again.",
        notFoundTitle = "Session not found",
        notFoundMessage = "The connected server does not have this session. It may have been deleted.",
        otherServerTitle = "This session belongs to another server",
        otherServerMessage = "The connection now serves a different server from the one this chat was opened on. " +
            "Close the chat and open the session from its own server.",
        unavailableTitle = "Session unavailable",
        unavailableReasons = mapOf(
            ChatFailure.NotConnected to "There is no connection to the server. Go back to the server list to connect again.",
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
