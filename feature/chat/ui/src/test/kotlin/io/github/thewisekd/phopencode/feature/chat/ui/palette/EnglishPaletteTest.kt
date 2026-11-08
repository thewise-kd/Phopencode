package io.github.thewisekd.phopencode.feature.chat.ui.palette

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInCommand
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.BuiltInNotice
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
        builtIns = mapOf(
            BuiltInCommand.New to listOf("/new", "Start a new session in this folder", "Also: /clear", "Built-in"),
            BuiltInCommand.Compact to listOf("/compact", "Summarize the session to free up context", "Also: /summarize", "Built-in"),
            BuiltInCommand.Fork to listOf("/fork", "Copy the session into a new one", "Built-in"),
            BuiltInCommand.Undo to listOf("/undo", "Undo the last turn", "Built-in"),
            BuiltInCommand.Redo to listOf("/redo", "Cancel the pending undo", "Built-in"),
            BuiltInCommand.Models to listOf("/models", "Switch the model", "Built-in"),
            BuiltInCommand.Agents to listOf("/agents", "Switch the agent", "Built-in"),
            BuiltInCommand.Sessions to
                listOf("/sessions", "Go back to the session list", "Also: /resume, /continue", "Built-in"),
        ),
        notices = mapOf(
            BuiltInNotice.Unavailable to "This command is not available right now.",
            BuiltInNotice.NothingToUndo to "There is no turn to undo.",
            BuiltInNotice.NothingToRedo to "There is no pending undo to cancel.",
        ),
        noticeDismiss = "Dismiss",
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
