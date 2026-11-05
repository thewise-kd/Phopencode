package io.github.thewisekd.phopencode.feature.chat.ui.subagent

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentStatus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "The subagents could not be loaded."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSubagentTest : SubagentLanguageContract() {
    override val strings = ExpectedSubagentStrings(
        badgeRunning = "2 running",
        badgeTotal = "3 subagents",
        badgeOne = "1 subagent",
        badgeOpen = "Show subagents",
        live = "Live",
        back = "Back to the parent session",
        headerList = "1 running",
        headerLoading = "Loading…",
        untitled = "Untitled subagent",
        title = "Subagents",
        summary = "3 subagents · 2 running",
        rowOpen = "Open its session",
        rows = mapOf(
            EXPLORE to listOf(
                EXPLORE_TITLE,
                "Running",
                "explore · 2 min 5 s · 14,190 tokens",
                "1,202 tool calls · 1 failed · Now: bash",
            ),
            NESTED to listOf(NESTED_TITLE, "Running", "general · 45 s"),
            REVIEW to listOf(REVIEW_TITLE, "Done", "general · 1 h 3 min · 1 token", "1 tool call"),
        ),
        statuses = mapOf(
            SubagentStatus.Running to "Running",
            SubagentStatus.Succeeded to "Done",
            SubagentStatus.Failed to "Failed",
            SubagentStatus.Interrupted to "Stopped",
            SubagentStatus.Idle to "Idle",
        ),
        durations = SAMPLE_DURATIONS.zip(
            listOf("0 s", "45 s", "2 min", "2 min 5 s", "1 h", "1 h 3 min", "1 h", "1,000 h"),
        ).toMap(),
        loading = "Loading subagents…",
        emptyTitle = "No subagents yet",
        errors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT There is no connection to the server.",
            ChatFailure.Unreachable to "$COULD_NOT The server could not be reached.",
            ChatFailure.Unauthorized to "$COULD_NOT The server refused the credentials.",
            ChatFailure.NotFound to "$COULD_NOT The session was not found.",
            ChatFailure.Conflict to "$COULD_NOT The server was busy.",
            ChatFailure.Rejected to "$COULD_NOT The server refused the request.",
            ChatFailure.ServerFailed to "$COULD_NOT The server ran into an error.",
            ChatFailure.Unknown to "$COULD_NOT An unexpected error occurred.",
        ),
        retry = "Try again",
    )
}
