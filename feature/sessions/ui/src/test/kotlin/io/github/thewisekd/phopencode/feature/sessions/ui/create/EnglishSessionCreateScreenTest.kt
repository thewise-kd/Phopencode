package io.github.thewisekd.phopencode.feature.sessions.ui.create

import io.github.thewisekd.phopencode.feature.sessions.logic.contract.CreateFolderError
import io.github.thewisekd.phopencode.feature.sessions.logic.contract.SessionsFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishSessionCreateScreenTest : SessionCreateScreenLanguageContract() {
    override val strings = ENGLISH_CREATE_STRINGS
}

internal val ENGLISH_CREATE_STRINGS = ExpectedCreateStrings(
    title = "New session",
    folder = "Folder",
    folderHelp = "An absolute path on the server, starting with /.",
    folderErrors = mapOf(
        CreateFolderError.Missing to "Enter the folder the session works in.",
        CreateFolderError.NotAbsolute to "The folder must be an absolute path that starts with /.",
    ),
    recent = "Recent folders",
    sessionTitle = "Title (optional)",
    sessionTitleHelp = "Leave it empty and the server names the session from your first message.",
    agent = "Agent",
    agentDefault = "Server default",
    model = "Model",
    modelDefault = "The server's default model",
    modelDefaultNamed = { "Default: $it" },
    modelChoose = "Choose a model",
    modelCancel = "Cancel",
    loading = "Loading agents and models…",
    skill = "The chosen skill is attached to your first message.",
    create = "Create session",
    creating = "Creating…",
    createError = { "The session could not be created. $it" },
    catalogError = { "Agents and models could not be loaded; the server's defaults are used. $it" },
    reasons = mapOf(
        SessionsFailure.NotConnected to "Not connected to the server. Go back and connect again.",
        SessionsFailure.Unreachable to "The server could not be reached.",
        SessionsFailure.Unauthorized to "The server refused the credentials.",
        SessionsFailure.NotFound to "The server could not find it.",
        SessionsFailure.Conflict to "Something changed on the server meanwhile. Try again.",
        SessionsFailure.Rejected to "The server refused the request.",
        SessionsFailure.ServerFailed to "The server ran into an error.",
        SessionsFailure.Unknown to "Something unexpected happened.",
    ),
    retry = "Try again",
    dismiss = "Dismiss error",
)
