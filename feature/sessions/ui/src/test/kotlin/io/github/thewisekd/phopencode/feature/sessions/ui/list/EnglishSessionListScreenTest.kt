package io.github.thewisekd.phopencode.feature.sessions.ui.list

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSessionListScreenTest : SessionListScreenLanguageContract() {
    override val strings = ENGLISH_SESSION_LIST_STRINGS
}

internal val ENGLISH_SESSION_LIST_STRINGS = ExpectedSessionListStrings(
    title = "Sessions",
    loading = "Loading sessions…",
    refreshing = "Refreshing sessions…",
    loadingMore = "Loading more sessions…",
    emptyTitle = "No sessions yet",
    emptyMessage = "Start a session to work with opencode on this server.",
    noMatchTitle = "No matching sessions",
    noMatchMessage = "No session title matches your search.",
    create = "New session",
    refresh = "Refresh sessions",
    openFiles = "Open files",
    openGit = "Open git",
    search = "Search sessions",
    showArchived = "Show archived",
    archived = "Archived",
    untitled = "Untitled session",
    manage = { "Manage $it" },
    retry = "Try again",
    dismiss = "Dismiss error",
    loadMoreError = "More sessions could not be loaded.",
    usage = "1,234,567 tokens · \$3.50",
    usageAboveZero = "1 token · more than \$0.00",
    badges = mapOf(
        SessionBadge.WaitingForInput to "Waiting for you",
        SessionBadge.Running to "Running",
        SessionBadge.Failed to "Failed",
        SessionBadge.Interrupted to "Interrupted",
    ),
    errors = mapOf(
        SessionsFailure.NotConnected to "Not connected to the server. Go back and connect again.",
        SessionsFailure.Unreachable to "The server could not be reached.",
        SessionsFailure.Unauthorized to "The server refused the login. Connect again with the right password.",
        SessionsFailure.NotFound to "The sessions could not be found on the server.",
        SessionsFailure.Conflict to "The sessions changed meanwhile. Try again.",
        SessionsFailure.Rejected to "The server refused to list the sessions.",
        SessionsFailure.ServerFailed to "The server failed while listing the sessions.",
        SessionsFailure.Unknown to "The sessions could not be loaded.",
    ),
)
