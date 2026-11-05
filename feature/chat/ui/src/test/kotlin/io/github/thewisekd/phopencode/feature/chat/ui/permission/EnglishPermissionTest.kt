package io.github.thewisekd.phopencode.feature.chat.ui.permission

import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val REASONS = mapOf(
    ChatFailure.NotConnected to "There is no connection to the server.",
    ChatFailure.Unreachable to "The server could not be reached. Try again.",
    ChatFailure.Unauthorized to "The server refused the credentials.",
    ChatFailure.NotFound to "The request is no longer waiting.",
    ChatFailure.Conflict to "The session is busy or changed meanwhile.",
    ChatFailure.Rejected to "The server refused the reply.",
    ChatFailure.ServerFailed to "The server failed.",
    ChatFailure.Unknown to "Something unexpected went wrong.",
)

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishPermissionTest : PermissionLanguageContract() {
    override val strings = ExpectedPermissionStrings(
        title = "Permission request",
        askerSelf = "Asked by this session",
        askerSubagent = { "Asked by a subagent (session $it)" },
        action = { "Action: $it" },
        meanings = mapOf(
            "bash" to "Wants to run a shell command",
            "edit" to "Wants to change files",
            "read" to "Wants to read files",
            "webfetch" to "Wants to fetch a web page",
            "external_directory" to "Wants to reach outside the project folder",
        ),
        noResources = "The server did not say what it applies to.",
        allowOnce = "Allow once",
        allowAlways = "Always allow",
        reject = "Reject",
        confirmTitle = "Always allow?",
        confirmSave = { "The server will save this rule and no longer ask before “$it” on:" },
        confirmNoSave = { "The server will save this and no longer ask before such “$it” requests." },
        wildcard = { "“*” matches everything: from now on every “$it” request goes ahead without asking." },
        confirmAlways = "Yes, always allow",
        cancel = "Cancel",
        sending = mapOf(
            PermissionDecision.Once to "Allowing once…",
            PermissionDecision.Always to "Allowing always…",
            PermissionDecision.Reject to "Rejecting…",
        ),
        error = { decision, reason ->
            when (decision) {
                PermissionDecision.Once -> "Could not allow once. $reason"
                PermissionDecision.Always -> "Could not allow always. $reason"
                PermissionDecision.Reject -> "Could not reject. $reason"
            }
        },
        reasons = REASONS,
        listError = { "Could not list the subagents' permission requests. $it" },
        listReasons = REASONS + mapOf(
            ChatFailure.NotFound to "The session is gone.",
            ChatFailure.Rejected to "The server refused the request.",
        ),
    )
}
