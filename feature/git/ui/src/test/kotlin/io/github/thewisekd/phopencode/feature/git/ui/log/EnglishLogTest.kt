package io.github.thewisekd.phopencode.feature.git.ui.log

import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class EnglishLogTest : LogLanguageContract() {
    override val strings = ExpectedLogStrings(
        locale = Locale.ENGLISH,
        january = "Jan",
        title = "History",
        refresh = "Refresh",
        loading = "Reading the history…",
        loadingMore = "Loading more commits…",
        end = "Start of the history",
        emptyTitle = "No commits yet",
        emptyMessage = "This branch has no commits.",
        filter = { "Branch: $it" },
        filterHead = "Current branch",
        merge = "Merge",
        retry = "Try again",
        readErrors = mapOf(
            GitFailure.NotConnected to "Not connected to the server. Go back and connect again.",
            GitFailure.Unreachable to "The server could not be reached.",
            GitFailure.TimedOut to "Git took too long to answer.",
            GitFailure.Unauthorized to "The server refused the login. Go back and connect again.",
            GitFailure.OutputTooLarge to "Git's answer is too large to show on the phone.",
            GitFailure.NotRepository to "This folder is not in a git repository.",
            GitFailure.GitMissing to "Git is not installed on the server.",
            GitFailure.Rejected to "The branch name or commit hash was refused.",
            GitFailure.NotFound to "The commit or branch is no longer there.",
        ),
        readFallback = "The history could not be read.",
        moreFallback = "More commits could not be read.",
        detailFallback = "The commit could not be read.",
        detailTitle = { "Commit $it" },
        detailLoading = "Reading the commit…",
        author = "Author",
        committer = "Committer",
        parent = "Parent",
        parents = "Parents",
        root = "Root commit, no parents",
        files = { if (it == 1) "1 file changed" else "$it files changed" },
        noFiles = "This commit changed no files.",
        binary = "Binary",
        counts = { added, removed ->
            val a = if (added == 1) "1 line added" else "$added lines added"
            val r = if (removed == 1) "1 line removed" else "$removed lines removed"
            "$a, $r"
        },
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
    )
}
