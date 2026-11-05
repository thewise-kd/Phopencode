package io.github.thewisekd.phopencode.feature.chat.ui.usage

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "The context limit could not be loaded."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishUsageTest : UsageLanguageContract() {
    override val strings = ExpectedUsageStrings(
        bar = "14,190 tokens · $0.12 · Context 7%",
        barTokensOnly = "14,190 tokens",
        barOneToken = "1 token",
        barLoading = "Counting tokens…",
        barEmpty = "No tokens used yet",
        barOpen = "Show token details",
        title = "Token usage",
        rows = mapOf(
            UsageTags.MODEL to ("Model" to "anthropic/claude-sonnet-4"),
            UsageTags.INPUT to ("Input" to "1,234"),
            UsageTags.OUTPUT to ("Output" to "567"),
            UsageTags.REASONING to ("Reasoning" to "89"),
            UsageTags.CACHE_READ to ("Cache read" to "12,000"),
            UsageTags.CACHE_WRITE to ("Cache write" to "300"),
            UsageTags.TOTAL to ("Total" to "14,190"),
            UsageTags.COST to ("Cost" to "$0.12"),
            UsageTags.CONTEXT_USED to ("Context in use" to "13,534 · 7%"),
            UsageTags.CONTEXT_LIMIT to ("Context limit" to "200,000"),
        ),
        costs = mapOf(
            ("1234.5" to false) to "$1,234.50",
            ("0" to false) to "$0.00",
            ("0.004" to false) to "under $0.01",
            ("1.5" to true) to "at least $1.50",
            ("0.004" to true) to "more than $0.00",
        ),
        costUnknown = "Not reported",
        loading = "Counting tokens…",
        emptyTitle = "No tokens used yet",
        errors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT There is no connection to the server.",
            ChatFailure.Unreachable to "$COULD_NOT The server could not be reached.",
            ChatFailure.Unauthorized to "$COULD_NOT The server refused the credentials.",
            ChatFailure.NotFound to "$COULD_NOT The model list was not found.",
            ChatFailure.Conflict to "$COULD_NOT The server was busy.",
            ChatFailure.Rejected to "$COULD_NOT The server refused the request.",
            ChatFailure.ServerFailed to "$COULD_NOT The server ran into an error.",
            ChatFailure.Unknown to "$COULD_NOT An unexpected error occurred.",
        ),
        retry = "Try again",
    )
}
