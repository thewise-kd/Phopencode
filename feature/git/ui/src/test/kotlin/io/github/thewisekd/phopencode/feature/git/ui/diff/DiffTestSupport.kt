package io.github.thewisekd.phopencode.feature.git.ui.diff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff

internal const val ROOT = "/srv/çalışma alanı"
internal const val FILE = "src/main kodu.kt"
internal const val OLD_FILE = "src/eski kod.kt"
internal const val HASH = "0123456789abcdef0123456789abcdef01234567"

internal val WORKING_ROUTE = GitRoute.FileDiff(ROOT, FILE, staged = false)
internal val STAGED_ROUTE = GitRoute.FileDiff(ROOT, FILE, staged = true)
internal val COMMIT_ROUTE = GitRoute.CommitFileDiff(ROOT, HASH, FILE)

internal fun context(text: String, old: Int, new: Int) = DiffLine(DiffLineKind.Context, text, old, new)
internal fun added(text: String, new: Int) = DiffLine(DiffLineKind.Added, text, null, new)
internal fun deleted(text: String, old: Int) = DiffLine(DiffLineKind.Deleted, text, old, null)
internal val NO_NEWLINE = DiffLine(DiffLineKind.NoNewline, "\\ No newline at end of file", null, null)

/** Two hunks with every line kind: 3 additions and 2 deletions. */
internal val SAMPLE_HUNKS = listOf(
    DiffHunk(
        oldStart = 1,
        oldCount = 3,
        newStart = 1,
        newCount = 4,
        section = "fun main()",
        lines = listOf(
            context("package demo", 1, 1),
            deleted("val a = 1", 2),
            added("val a = 2", 2),
            added("val b = 3", 3),
            context("", 3, 4),
        ),
    ),
    DiffHunk(
        oldStart = 40,
        oldCount = 1,
        newStart = 41,
        newCount = 1,
        lines = listOf(deleted("end", 40), NO_NEWLINE, added("end", 41), NO_NEWLINE),
    ),
)

internal fun fileDiff(
    content: DiffContent = DiffContent.Text(SAMPLE_HUNKS),
    kind: GitChangeKind = GitChangeKind.Modified,
    oldPath: String? = null,
    additions: Int = 3,
    deletions: Int = 2,
) = GitFileDiff(path = FILE, oldPath = oldPath, kind = kind, content = content, additions = additions, deletions = deletions)

internal fun loaded(diff: GitFileDiff, route: GitRoute = WORKING_ROUTE) =
    DiffState(target = targetOf(route), loading = false, diff = diff)

internal fun targetOf(route: GitRoute): DiffTarget = when (route) {
    is GitRoute.FileDiff -> DiffTarget.of(route)
    is GitRoute.CommitFileDiff -> DiffTarget.of(route)
    else -> error("not a diff route")
}

/** The diff screen in the app theme, with a state the test can change and every intent recorded. */
internal class DiffHarness(val route: GitRoute, initial: DiffState, private val dark: Boolean) {
    val state: MutableState<DiffState> = mutableStateOf(initial)
    val intents = mutableListOf<DiffIntent>()

    @Composable
    fun Content() {
        PhopencodeTheme(darkTheme = dark) {
            DiffScreen(route = route, state = state.value, onIntent = { intents += it })
        }
    }
}

internal fun ComposeContentTestRule.setDiff(
    state: DiffState,
    route: GitRoute = WORKING_ROUTE,
    dark: Boolean = false,
): DiffHarness {
    val harness = DiffHarness(route, state, dark)
    setContent { harness.Content() }
    return harness
}

/** Scrolls the list to the row of line [line] in hunk [hunk]; on a short screen it is not composed yet. */
internal fun ComposeContentTestRule.lineRow(hunk: Int, line: Int): SemanticsNodeInteraction {
    onNodeWithTag(DiffTags.LIST).performScrollToNode(hasTestTag(DiffTags.line(hunk, line)))
    return onNodeWithTag(DiffTags.line(hunk, line))
}

/** One part of a line row ([DiffTags.MARKER], [DiffTags.TEXT], a gutter), which merges into the row. */
internal fun ComposeContentTestRule.linePart(hunk: Int, line: Int, part: String): SemanticsNodeInteraction =
    onNode(hasTestTag(part) and hasAnyAncestor(hasTestTag(DiffTags.line(hunk, line))), useUnmergedTree = true)

internal fun ComposeContentTestRule.scrollListTo(tag: String): SemanticsNodeInteraction {
    onNodeWithTag(DiffTags.LIST).performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}
