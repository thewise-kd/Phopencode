package io.github.thewisekd.phopencode.feature.git.ui.commit

import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange

/** Made-up paths and messages: no real server layout. */
internal const val ROOT = "/work/demo proje"

internal val route = GitRoute.Commit(ROOT)

internal val stagedFile = GitFileChange("src/main/Hazır.kt", GitChangeKind.Added)
internal val renamedFile = GitFileChange("docs/new name.md", GitChangeKind.Renamed, originalPath = "docs/old name.md")
internal val modifiedFile = GitFileChange("README.md", GitChangeKind.Modified)
internal val deletedFile = GitFileChange("lib/gone.txt", GitChangeKind.Deleted)
internal val untrackedFile = GitFileChange("notes/todo list.txt", GitChangeKind.Untracked)
internal val conflictedFile = GitFileChange("src/çakışma.kt", GitChangeKind.Unmerged)

internal val loaded = CommitState(
    directory = ROOT,
    loading = false,
    message = "Ayrıştırıcıyı düzelt",
    staged = listOf(stagedFile, renamedFile),
    unstaged = listOf(modifiedFile, deletedFile, untrackedFile, conflictedFile),
)

internal val clean = CommitState(directory = ROOT, loading = false)

internal fun ComposeContentTestRule.setCommitScreen(state: CommitState, onIntent: (CommitIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { CommitScreen(route = route, state = state, onIntent = onIntent) }
        }
    }
}

/** Shows the state [holder] holds, so that a test can change it after the content is set. */
internal fun ComposeContentTestRule.setCommitScreen(
    holder: MutableState<CommitState>,
    onIntent: (CommitIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { CommitScreen(route = route, state = holder.value, onIntent = onIntent) }
        }
    }
}

internal fun stateHolder(state: CommitState): MutableState<CommitState> = mutableStateOf(state)

/** The list composes only the rows in view: scroll it until [matcher]'s node exists. */
internal fun ComposeContentTestRule.scrollCommitTo(matcher: SemanticsMatcher) {
    onNodeWithTag(CommitTags.LIST).performScrollToNode(matcher)
}

/** The row of [change] in the staged or the unstaged list: its file name is its headline. */
internal fun rowOf(staged: Boolean, change: GitFileChange): SemanticsMatcher =
    hasTestTag(CommitTags.row(staged)) and hasText(fileName(change.path))
