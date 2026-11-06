package io.github.thewisekd.phopencode.feature.git.ui.status

import androidx.compose.material3.Surface
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState

/** Made-up paths and branch names: no real server layout. */
internal const val ROOT = "/work/demo proje"
internal const val HASH = "0123456789abcdef0123456789abcdef01234567"

internal val route = GitRoute.Status(ROOT)

internal val conflictedFile = GitFileChange("src/çakışma.kt", GitChangeKind.Unmerged)
internal val stagedFile = GitFileChange("src/main/Hazır.kt", GitChangeKind.Added)
internal val renamedFile = GitFileChange("docs/new name.md", GitChangeKind.Renamed, originalPath = "docs/old name.md")
internal val unstagedFile = GitFileChange("README.md", GitChangeKind.Modified)
internal val deletedFile = GitFileChange("lib/gone.txt", GitChangeKind.Deleted)
internal val untrackedFile = GitFileChange("notes/todo list.txt", GitChangeKind.Untracked)

internal val branch = GitBranchStatus(head = "main", commit = HASH, upstream = "origin/main", ahead = 2, behind = 1)

internal val loaded = StatusState(
    directory = ROOT,
    loading = false,
    branch = branch,
    conflicted = listOf(conflictedFile),
    staged = listOf(stagedFile, renamedFile),
    unstaged = listOf(unstagedFile, deletedFile),
    untracked = listOf(untrackedFile),
)

internal val clean = StatusState(directory = ROOT, loading = false, branch = branch.copy(ahead = 0, behind = 0))

internal fun ComposeContentTestRule.setStatusScreen(state: StatusState, onIntent: (StatusIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { StatusScreen(route = route, state = state, onIntent = onIntent) }
        }
    }
}

/** The list composes only the rows in view: scroll it until [matcher]'s node exists. */
internal fun ComposeContentTestRule.scrollStatusTo(matcher: SemanticsMatcher) {
    onNodeWithTag(StatusTags.LIST).performScrollToNode(matcher)
}

/** The row of [change] in [group]: its file name is its headline. */
internal fun rowOf(group: StatusGroup, change: GitFileChange): SemanticsMatcher =
    hasTestTag(StatusTags.row(group)) and hasText(fileName(change.path))
