package io.github.thewisekd.phopencode.feature.chat.ui.actions

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SessionAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishActionsTest : ActionsLanguageContract() {
    override val strings = ExpectedActionsStrings(
        menuButton = "Session actions",
        menuTitle = "Session actions",
        close = "Close",
        compact = "Compact session",
        compactDetail = "Summarize the conversation so far to free up the model's context.",
        fork = "Fork session",
        forkDetail = "Copy the whole conversation into a new session.",
        busy = "Wait until the current action finishes.",
        compactTitle = "Compact this session?",
        compactMessage = "The server summarizes the conversation so far, and the model continues from the summary. This can take a while.",
        compactConfirm = "Compact",
        forkTitle = "Fork this session?",
        forkMessage = "A new session starts with a copy of the whole conversation, and the chat switches to it. This session stays as it is.",
        forkHereTitle = "Fork from here?",
        forkHereMessage = "A new session starts with the conversation before this message, and the chat switches to it. " +
            "This session stays as it is.",
        forkConfirm = "Fork",
        cancel = "Cancel",
        compacting = "Compacting…",
        forking = "Forking…",
        creating = "Starting a new session…",
        error = { action, reason ->
            when (action) {
                SessionAction.Compact -> "The session could not be compacted. $reason"
                is SessionAction.Fork -> "The session could not be forked. $reason"
                SessionAction.NewSession -> "A new session could not be started. $reason"
            }
        },
        reasons = mapOf(
            ChatFailure.NotConnected to "There is no connection to the server.",
            ChatFailure.Unreachable to "The server could not be reached.",
            ChatFailure.Unauthorized to "The server refused the credentials.",
            ChatFailure.NotFound to "The session or the message no longer exists.",
            ChatFailure.Conflict to "The session is busy or changed meanwhile.",
            ChatFailure.Rejected to "The server refused the request.",
            ChatFailure.ServerFailed to "The server ran into an error.",
            ChatFailure.Unknown to "An unexpected error occurred.",
        ),
        turnChanges = "This turn's changes",
        forkHere = "Fork from here",
        closeActions = "Close actions",
    )
}
