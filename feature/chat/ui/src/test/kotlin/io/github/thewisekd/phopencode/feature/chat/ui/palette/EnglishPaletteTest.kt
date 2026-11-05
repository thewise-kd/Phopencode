package io.github.thewisekd.phopencode.feature.chat.ui.palette

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val REASONS = mapOf(
    ChatFailure.NotConnected to "There is no connection to the server.",
    ChatFailure.Unreachable to "The server could not be reached.",
    ChatFailure.Unauthorized to "The server refused the credentials.",
    ChatFailure.NotFound to "The session was not found.",
    ChatFailure.Conflict to "The server was busy.",
    ChatFailure.Rejected to "The server refused the request.",
    ChatFailure.ServerFailed to "The server ran into an error.",
    ChatFailure.Unknown to "An unexpected error occurred.",
)

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPaletteTest : PaletteLanguageContract() {
    override val strings = ExpectedPaletteStrings(
        titleSlash = "Commands and skills",
        titleAt = "Agents and files",
        close = "Close",
        rows = mapOf(
            INIT to listOf("/init", "Create AGENTS.md", "Command"),
            REVIEW to listOf("/review", REVIEW_DESCRIPTION, "Command"),
            PDF to listOf("pdf-özet", "Skill"),
            EXPLORE to listOf("Explore", "Searches the code", "Agent"),
            SOURCE to listOf("src/çekirdek/Main.kt", "File"),
            FOLDER to listOf("src/çekirdek", "Folder"),
        ),
        run = "Run",
        attach = "Attach",
        loading = "Loading…",
        empty = "Nothing matches",
        running = "Running the command…",
        loadErrors = REASONS.mapValues { "The list could not be loaded. ${it.value}" },
        commandErrors = REASONS.mapValues { "The command could not be run. ${it.value}" },
        retry = "Try again",
        dismiss = "Dismiss error",
        chips = mapOf(
            FILE_ATTACHMENT to listOf("File", "src/çekirdek/Main.kt"),
            AGENT_ATTACHMENT to listOf("Agent", "explore"),
            SKILL_ATTACHMENT to listOf("Skill", "pdf-özet"),
        ),
        remove = mapOf(
            FILE_ATTACHMENT to "Remove the file src/çekirdek/Main.kt",
            AGENT_ATTACHMENT to "Remove the agent explore",
            SKILL_ATTACHMENT to "Remove the skill pdf-özet",
        ),
    )
}
