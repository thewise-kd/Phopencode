package io.github.thewisekd.phopencode.feature.git.ui.status

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** The texts of the status screen in one language. A format takes a file name, a branch or a count. */
internal data class ExpectedStatusStrings(
    val title: String,
    val refresh: String,
    val loading: String,
    val refreshing: String,
    val detached: (String) -> String,
    val noCommits: String,
    val tracking: (String) -> String,
    val ahead: (Int) -> String,
    val behind: (Int) -> String,
    val upToDate: String,
    val noUpstream: String,
    /** The upstream the branch tracked, deleted on the remote. */
    val upstreamGone: (String) -> String,
    val countsUnknown: String,
    val commit: String,
    val log: String,
    val branches: String,
    val remote: String,
    val groups: Map<StatusGroup, String>,
    val stageAll: String,
    val unstageAll: String,
    val stage: (String) -> String,
    val unstage: (String) -> String,
    val resolve: (String) -> String,
    val kinds: Map<GitChangeKind, String>,
    val renamedFrom: (String) -> String,
    val cleanTitle: String,
    val cleanMessage: String,
    val retry: String,
    /** The read failures the screen words on its own, and the fallback for the rest. */
    val readErrors: Map<GitFailure, String>,
    val readFallback: String,
    val actionFallback: String,
)

/** The failures each language words on its own; the others take the fallback. */
internal val WORDED = listOf(
    GitFailure.NotConnected,
    GitFailure.Unreachable,
    GitFailure.TimedOut,
    GitFailure.Unauthorized,
    GitFailure.OutputTooLarge,
    GitFailure.NotRepository,
    GitFailure.GitMissing,
    GitFailure.Rejected,
    GitFailure.NotFound,
    GitFailure.Conflict,
)

internal val FALLBACK = listOf(
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    GitFailure.GitError(exitCode = 128),
    GitFailure.GitError(exitCode = null),
    GitFailure.Unknown,
)

/** What the status screen shows, the same in each language; a subclass sets the language and its texts. */
abstract class StatusLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedStatusStrings

    @Test
    fun `the top bar and the hub actions are named`() {
        compose.setStatusScreen(loaded)

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        for (text in listOf(strings.commit, strings.log, strings.branches, strings.remote)) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the group headers are named`() {
        compose.setStatusScreen(loaded)

        StatusGroup.entries.forEach { group ->
            val header = hasTestTag(StatusTags.header(group)) and hasText(strings.groups.getValue(group))
            compose.scrollStatusTo(header)
            compose.onNode(header).assertIsDisplayed()
        }
        compose.scrollStatusTo(hasText(strings.unstageAll))
        compose.onNodeWithText(strings.unstageAll).assertIsDisplayed()
        compose.scrollStatusTo(hasText(strings.stageAll))
        compose.onNodeWithText(strings.stageAll).assertIsDisplayed()
    }

    @Test
    fun `each row's action names its file`() {
        compose.setStatusScreen(loaded)

        listOf(
            rowOf(StatusGroup.Conflicted, conflictedFile) to strings.resolve(fileName(conflictedFile.path)),
            rowOf(StatusGroup.Staged, stagedFile) to strings.unstage(fileName(stagedFile.path)),
            rowOf(StatusGroup.Unstaged, unstagedFile) to strings.stage(fileName(unstagedFile.path)),
            rowOf(StatusGroup.Untracked, untrackedFile) to strings.stage(fileName(untrackedFile.path)),
        ).forEach { (row, description) ->
            compose.scrollStatusTo(row)
            compose.onNode(hasContentDescription(description) and hasAnyAncestor(row)).assertIsDisplayed()
        }
    }

    @Test
    fun `every kind of change is worded`() {
        assertEquals(GitChangeKind.entries.toSet(), strings.kinds.keys)
        assertEquals(GitChangeKind.entries.size, strings.kinds.values.toSet().size, "kinds must differ")
        val rows = GitChangeKind.entries.mapIndexed { index, kind -> GitFileChange("dir/file-$index.txt", kind) }
        compose.setStatusScreen(StatusState(directory = ROOT, loading = false, unstaged = rows))

        rows.forEach { change ->
            val row = rowOf(StatusGroup.Unstaged, change) and
                hasText(strings.kinds.getValue(change.kind), substring = true)
            compose.scrollStatusTo(row)
            compose.onNode(row).assertIsDisplayed()
        }
    }

    @Test
    fun `a rename names the file it came from`() {
        compose.setStatusScreen(loaded)

        val row = rowOf(StatusGroup.Staged, renamedFile) and
            hasText(strings.renamedFrom(renamedFile.originalPath!!), substring = true)
        compose.scrollStatusTo(row)
        compose.onNode(row).assertIsDisplayed()
    }

    @Test
    fun `the upstream line counts commits ahead and behind`() {
        compose.setStatusScreen(loaded)

        val upstream = compose.onNodeWithTag(StatusTags.UPSTREAM)
        upstream.assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.tracking("origin/main"), substring = true))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.ahead(2), substring = true))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.behind(1), substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `one commit ahead is worded for one`() {
        compose.setStatusScreen(clean.copy(branch = branch.copy(ahead = 1, behind = 0)))

        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.ahead(1), substring = true))
            .assertIsDisplayed()
        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.behind(0), substring = true))
            .assertDoesNotExist()
    }

    @Test
    fun `an even branch is up to date`() {
        compose.setStatusScreen(clean)

        compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(strings.upToDate, substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a gone upstream is named as gone, not as up to date or as none`() {
        compose.setStatusScreen(clean.copy(branch = branch.copy(ahead = 0, behind = 0, upstreamGone = true)))

        compose.onNode(hasTestTag(StatusTags.UPSTREAM_GONE) and hasText(strings.upstreamGone("origin/main")))
            .assertIsDisplayed()
        compose.onNode(hasText(strings.upToDate, substring = true)).assertDoesNotExist()
        compose.onNode(hasText(strings.noUpstream, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `uncounted ahead and behind are not called up to date`() {
        compose.setStatusScreen(clean.copy(branch = branch.copy(ahead = 0, behind = 0, aheadBehindKnown = false)))

        for (part in listOf(strings.tracking("origin/main"), strings.countsUnknown)) {
            compose.onNode(hasTestTag(StatusTags.UPSTREAM) and hasText(part, substring = true)).assertIsDisplayed()
        }
        compose.onNode(hasText(strings.upToDate, substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a branch without an upstream says so`() {
        compose.setStatusScreen(clean.copy(branch = GitBranchStatus(head = "main", commit = HASH)))

        compose.onNodeWithText(strings.noUpstream).assertIsDisplayed()
    }

    @Test
    fun `a detached head is worded`() {
        compose.setStatusScreen(clean.copy(branch = GitBranchStatus(head = null, commit = HASH)))

        compose.onNodeWithText(strings.detached("0123456")).assertIsDisplayed()
    }

    @Test
    fun `a branch without commits says so`() {
        compose.setStatusScreen(clean.copy(branch = GitBranchStatus(head = "main")))

        compose.onNode(hasTestTag(StatusTags.BRANCH) and hasText(strings.noCommits, substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `a clean repository is worded`() {
        compose.setStatusScreen(clean)

        compose.onNodeWithText(strings.cleanTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.cleanMessage).assertIsDisplayed()
    }

    @Test
    fun `the first load is worded`() {
        compose.setStatusScreen(StatusState(directory = ROOT))
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `a reload is worded`() {
        compose.setStatusScreen(loaded.copy(refreshing = true))
        compose.onNodeWithContentDescription(strings.refreshing).assertIsDisplayed()
    }

    @Test
    fun `the worded failures differ`() {
        assertEquals(WORDED.toSet(), strings.readErrors.keys)
        val texts = WORDED.map { strings.readErrors.getValue(it) } + strings.readFallback + strings.actionFallback
        assertEquals(texts.size, texts.toSet().size, "every worded failure must have its own text")
    }

    @Test
    fun `every worded failure is explained when the status cannot be read`() {
        WORDED.forEach { failure ->
            val shown = StatusState(directory = ROOT, loading = false, error = failure)
            assertReadError(shown, strings.readErrors.getValue(failure))
        }
    }

    @Test
    fun `other failures reading the status share the fallback`() {
        FALLBACK.forEach { failure ->
            assertReadError(StatusState(directory = ROOT, loading = false, error = failure), strings.readFallback)
        }
    }

    @Test
    fun `a failed stage is explained in its own words`() {
        compose.setStatusScreen(loaded.copy(actionError = GitFailure.Unknown))

        compose.onNodeWithTag(StatusTags.ACTION_ERROR).assertIsDisplayed()
        compose.onAllNodesWithText(strings.actionFallback).assertCountEquals(1)
    }

    @Test
    fun `a worded failure staging uses its own text`() {
        compose.setStatusScreen(loaded.copy(actionError = GitFailure.NotFound))

        compose.onAllNodesWithText(strings.readErrors.getValue(GitFailure.NotFound)).assertCountEquals(1)
    }

    @Test
    fun `retry is offered after a failed read`() {
        compose.setStatusScreen(StatusState(directory = ROOT, loading = false, error = GitFailure.Unreachable))

        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    /** Content can be set once per rule: a state holder swaps the state shown for each failure. */
    private val shownState = mutableStateOf<StatusState?>(null)
    private var started = false

    private fun assertReadError(state: StatusState, message: String) {
        shownState.value = state
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface {
                        shownState.value?.let { StatusScreen(route = route, state = it, onIntent = {}) }
                    }
                }
            }
        }
        compose.onNodeWithTag(StatusTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithText(message).assertCountEquals(1)
    }
}
