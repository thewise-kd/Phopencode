package io.github.thewisekd.phopencode.feature.git.ui.status

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishStatusTest : StatusLanguageContract() {
    override val strings = ExpectedStatusStrings(
        title = "Git",
        refresh = "Refresh",
        loading = "Reading the status…",
        refreshing = "Refreshing…",
        detached = { "Detached HEAD at $it" },
        noCommits = "No commits yet",
        tracking = { "Tracking $it" },
        ahead = { "$it ahead" },
        behind = { "$it behind" },
        upToDate = "Up to date",
        noUpstream = "No upstream branch",
        upstreamGone = { "Upstream $it is gone" },
        countsUnknown = "Ahead and behind not counted",
        commit = "Commit",
        log = "History",
        branches = "Branches",
        remote = "Remote",
        groups = mapOf(
            StatusGroup.Conflicted to "Conflicts",
            StatusGroup.Staged to "Staged",
            StatusGroup.Unstaged to "Not staged",
            StatusGroup.Untracked to "Untracked",
        ),
        stageAll = "Stage all",
        unstageAll = "Unstage all",
        stage = { "Stage $it" },
        unstage = { "Unstage $it" },
        resolve = { "Mark $it resolved" },
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
        renamedFrom = { "from $it" },
        cleanTitle = "Nothing to commit",
        cleanMessage = "The working tree is clean.",
        retry = "Try again",
        readErrors = mapOf(
            GitFailure.NotConnected to "Not connected to the server. Go back and connect again.",
            GitFailure.Unreachable to "The server could not be reached.",
            GitFailure.TimedOut to "Git took too long to answer.",
            GitFailure.Unauthorized to "The server refused the login. Go back and connect again.",
            GitFailure.OutputTooLarge to "The status is too large to show on the phone.",
            GitFailure.NotRepository to "This folder is not in a git repository.",
            GitFailure.GitMissing to "Git is not installed on the server.",
            GitFailure.Rejected to "The file name was refused.",
            GitFailure.NotFound to "The file or folder is no longer there.",
            GitFailure.Conflict to "The repository has unresolved conflicts.",
        ),
        readFallback = "The status could not be read.",
        actionFallback = "The file could not be staged or unstaged.",
    )
}
