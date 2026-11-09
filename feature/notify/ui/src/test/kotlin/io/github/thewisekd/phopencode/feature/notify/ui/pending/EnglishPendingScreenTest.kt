package io.github.thewisekd.phopencode.feature.notify.ui.pending

import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPendingScreenTest : PendingScreenLanguageContract() {
    override val strings = ENGLISH_PENDING_STRINGS
}

internal val ENGLISH_PENDING_STRINGS = ExpectedPendingStrings(
    title = "Waiting for you",
    refresh = "Refresh the list",
    loading = "Loading what is waiting…",
    refreshing = "Refreshing what is waiting…",
    empty = "Nothing is waiting for an answer",
    question = "Question",
    form = "Form",
    permission = "Permission request",
    untitled = "Untitled session",
    subagent = "Subagent",
    open = "Open session",
    retry = "Try again",
    connect = "Connect",
    errors = mapOf(
        NotifyFailure.NotConnected to "Not connected to a server. Connect to see what is waiting.",
        NotifyFailure.Unreachable to "The server could not be reached.",
        NotifyFailure.Unauthorized to "The server refused the sign-in. Connect again with the right password.",
        NotifyFailure.NotFound to "The server could not find what was asked.",
        NotifyFailure.Rejected to "The server refused to list what is waiting.",
        NotifyFailure.ServerFailed to "The server failed while listing what is waiting.",
        NotifyFailure.Unknown to "What is waiting could not be loaded.",
    ),
)
