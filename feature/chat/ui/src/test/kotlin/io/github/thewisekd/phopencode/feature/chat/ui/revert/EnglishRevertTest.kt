package io.github.thewisekd.phopencode.feature.chat.ui.revert

import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertAction
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val COULD_NOT = "The changes could not be loaded."
private const val UNKNOWN = "An unexpected error occurred."

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishRevertTest : RevertLanguageContract() {
    override val strings = ExpectedRevertStrings(
        title = "Changes in this turn",
        summary = "3 files changed · +1,235 −1",
        summaryOne = "1 file changed · +1 −0",
        statuses = mapOf(
            VcsFileStatus.Added to "Added",
            VcsFileStatus.Modified to "Modified",
            VcsFileStatus.Deleted to "Deleted",
            VcsFileStatus.Renamed to "Renamed",
            VcsFileStatus.Untracked to "Untracked",
            VcsFileStatus.Unknown("copied") to "copied",
        ),
        additions = "+1,234",
        deletions = "−1",
        showDiff = "Show diff",
        hideDiff = "Hide diff",
        expanded = "Expanded",
        collapsed = "Collapsed",
        noPatch = "No diff to show. It may be a binary file.",
        moreLines = "200 more lines",
        moreLine = "1 more line",
        moreCharacters = "1,500 more characters",
        loading = "Loading the changes…",
        emptyTitle = "No file changes",
        emptyMessage = "This turn changed no files.",
        intro = "Reverting removes this message and everything after it, and undoes their file changes. " +
            "You confirm before it is final.",
        revert = "Revert",
        confirmTitle = "Revert this turn?",
        confirmMessage = "This message and everything after it will be removed, and the changes to 2 files will be undone.",
        confirmMessageOne = "This message and everything after it will be removed, and the changes to 1 file will be undone.",
        confirmMessageNone = "This message and everything after it will be removed. No files change.",
        cancel = "Cancel",
        otherTurn = "A revert of another message is waiting for confirmation. Cancel it to revert this one.",
        pending = mapOf(
            RevertAction.Stage to "Preparing the revert…",
            RevertAction.Commit to "Reverting…",
            RevertAction.Cancel to "Cancelling the revert…",
        ),
        stepErrors = mapOf(
            RevertAction.Stage to "The revert could not be prepared. $UNKNOWN",
            RevertAction.Commit to "The turn could not be reverted. $UNKNOWN",
            RevertAction.Cancel to "The revert could not be cancelled. $UNKNOWN",
        ),
        loadErrors = mapOf(
            ChatFailure.NotConnected to "$COULD_NOT There is no connection to the server.",
            ChatFailure.Unreachable to "$COULD_NOT The server could not be reached.",
            ChatFailure.Unauthorized to "$COULD_NOT The server refused the credentials.",
            ChatFailure.NotFound to "$COULD_NOT The message or its session no longer exists.",
            ChatFailure.Conflict to "$COULD_NOT The session is busy or changed meanwhile.",
            ChatFailure.Rejected to "$COULD_NOT The server refused the request.",
            ChatFailure.ServerFailed to "$COULD_NOT The server ran into an error.",
            ChatFailure.Unknown to "$COULD_NOT $UNKNOWN",
        ),
        retry = "Try again",
    )
}
