package io.github.thewisekd.phopencode.feature.sessions.ui.list

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionBadge
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    emptyMessage = "Each chat is an opencode session that works in a folder on this server.",
    noMatchTitle = "No matching sessions",
    noMatchMessage = "No session title matches your search.",
    create = "New chat",
    refresh = "Refresh sessions",
    openFiles = "Open files",
    openGit = "Open git",
    catalog = "More options",
    catalogItems = mapOf(
        CatalogDestination.Skills to "Skills",
        CatalogDestination.Registry to "Commands, agents and models",
        CatalogDestination.Status to "MCP and plugin status",
    ),
    notifyItems = mapOf(
        NotifyDestination.Setup to "Notifications",
        NotifyDestination.Pending to "Pending items",
    ),
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
    ages = mapOf(
        20.seconds to "just now",
        1.minutes to "1 min ago",
        5.minutes to "5 min ago",
        3.hours to "3 h ago",
        1.days to "1 day ago",
        4.days to "4 days ago",
        30.days to "Feb 13",
        400.days to "Feb 8, 2025",
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
