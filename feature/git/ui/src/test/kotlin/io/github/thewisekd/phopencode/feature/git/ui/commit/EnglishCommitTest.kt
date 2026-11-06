package io.github.thewisekd.phopencode.feature.git.ui.commit

import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishCommitTest : CommitLanguageContract() {
    override val strings = ExpectedCommitStrings(
        title = "Commit",
        refresh = "Refresh",
        loading = "Reading the changes…",
        messageLabel = "Commit message",
        messageBlank = "Write a commit message first.",
        commit = "Commit",
        busy = mapOf(
            CommitOp.Stage to "Staging…",
            CommitOp.Unstage to "Unstaging…",
            CommitOp.Discard to "Discarding…",
            CommitOp.Commit to "Committing…",
        ),
        groupStaged = "Staged",
        groupUnstaged = "Not staged",
        stageAll = "Stage all",
        unstageAll = "Unstage all",
        nothingStagedHint = "Nothing is staged yet. Stage the files to commit.",
        stage = { "Stage $it" },
        unstage = { "Unstage $it" },
        discard = { "Discard changes to $it" },
        renamedFrom = { "from $it" },
        kinds = mapOf(
            GitChangeKind.Added to "Added",
            GitChangeKind.Modified to "Modified",
            GitChangeKind.Deleted to "Deleted",
            GitChangeKind.Renamed to "Renamed",
            GitChangeKind.Copied to "Copied",
            GitChangeKind.TypeChanged to "Type changed",
            GitChangeKind.Untracked to "New file",
            GitChangeKind.Unmerged to "Conflict",
        ),
        emptyTitle = "Nothing to commit",
        emptyMessage = "The working tree is clean.",
        discardTitle = "Discard changes?",
        discardMessage = { "The changes to $it that are not staged will be lost for good. This cannot be undone." },
        discardConfirm = "Discard",
        cancel = "Cancel",
        hookRejected = "A hook refused the commit. Nothing was committed.",
        hookNoOutput = "The hook printed nothing.",
        nothingStaged = "Nothing is staged. Stage a file before committing.",
        errors = mapOf(
            GitFailure.NotConnected to "Not connected to the server. Go back and connect again.",
            GitFailure.Unreachable to "The server could not be reached.",
            GitFailure.TimedOut to "Git took too long to answer.",
            GitFailure.Unauthorized to "The server refused the login. Go back and connect again.",
            GitFailure.OutputTooLarge to "The answer is too large to show on the phone.",
            GitFailure.NotRepository to "This folder is not in a git repository.",
            GitFailure.GitMissing to "Git is not installed on the server.",
            GitFailure.Rejected to "The file name or the message was refused.",
            GitFailure.NotFound to "The file or folder is no longer there.",
            GitFailure.Conflict to "The repository has unresolved conflicts.",
        ),
        fallbacks = mapOf(
            CommitOp.Stage to "The file could not be staged.",
            CommitOp.Unstage to "The file could not be unstaged.",
            CommitOp.Discard to "The changes could not be discarded.",
            CommitOp.Commit to "The commit could not be made.",
        ),
    )
}
