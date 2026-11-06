package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

/** The texts of the history and commit detail screens in one language. */
internal data class ExpectedLogStrings(
    val locale: Locale,
    /** A short month name of [AUTHORED], so that the date is seen to be in the language. */
    val january: String,
    val title: String,
    val refresh: String,
    val loading: String,
    val loadingMore: String,
    val end: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val filter: (String) -> String,
    val filterHead: String,
    val merge: String,
    val retry: String,
    val readErrors: Map<GitFailure, String>,
    val readFallback: String,
    val moreFallback: String,
    val detailFallback: String,
    val detailTitle: (String) -> String,
    val detailLoading: String,
    val author: String,
    val committer: String,
    val parent: String,
    val parents: String,
    val root: String,
    val files: (Int) -> String,
    val noFiles: String,
    val binary: String,
    val counts: (Int, Int) -> String,
    val renamedFrom: (String) -> String,
    val kinds: Map<GitChangeKind, String>,
)

/** The failures each language words on its own; the others take a fallback. */
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
)

internal val FALLBACK = listOf(
    GitFailure.CredentialRequired,
    GitFailure.NonFastForward,
    GitFailure.Diverged,
    GitFailure.WouldOverwrite,
    GitFailure.Conflict,
    GitFailure.GitError(exitCode = 128),
    GitFailure.GitError(exitCode = null),
    GitFailure.Unknown,
)

/** What the history screens show, the same in each language; a subclass sets the language and its texts. */
abstract class LogLanguageContract {
    @get:Rule
    val compose = createComposeRule()

    internal abstract val strings: ExpectedLogStrings

    @Test
    fun `the history is worded`() {
        compose.setLogScreen(loadedLog.copy(endReached = true))

        compose.onNodeWithText(strings.title).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.refresh).assertIsDisplayed()
        compose.onNodeWithText(strings.filter(strings.filterHead)).assertIsDisplayed()
        compose.scrollLogTo(hasTestTag(LogTags.ROW) and hasText(mergeCommit.subject, substring = true))
        compose.onNode(hasTestTag(LogTags.ROW) and hasText(strings.merge, substring = true)).assertIsDisplayed()
        compose.scrollLogTo(hasTestTag(LogTags.END))
        compose.onNodeWithText(strings.end).assertIsDisplayed()
    }

    @Test
    fun `a filtered history names its branch`() {
        compose.setLogScreen(loadedLog.copy(branch = "feature/giriş", endReached = true))

        compose.onNodeWithText(strings.filter("feature/giriş")).assertIsDisplayed().performClick()
        compose.onNode(hasTestTag(LogTags.filterOption(null)) and hasText(strings.filterHead)).assertIsDisplayed()
    }

    @Test
    fun `dates follow the language`() {
        compose.setLogScreen(loadedLog.copy(endReached = true))

        val date = expectedDate(AUTHORED, strings.locale)
        assertEquals(true, date.contains(strings.january), "the expected date $date names ${strings.january}")
        compose.onNode(hasTestTag(LogTags.ROW) and hasText(plainCommit.subject, substring = true) and hasText(date, substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun `the first load is worded`() {
        compose.setLogScreen(LogState(directory = ROOT))
        compose.onNodeWithContentDescription(strings.loading).assertIsDisplayed()
    }

    @Test
    fun `loading more is worded`() {
        compose.setLogScreen(loadedLog.copy(loadingMore = true))
        compose.scrollLogTo(hasTestTag(LogTags.LOADING_MORE))
        compose.onNodeWithContentDescription(strings.loadingMore).assertIsDisplayed()
    }

    @Test
    fun `an empty branch is worded`() {
        compose.setLogScreen(LogState(directory = ROOT, loading = false, endReached = true))
        compose.onNodeWithText(strings.emptyTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.emptyMessage).assertIsDisplayed()
    }

    @Test
    fun `the worded failures differ`() {
        assertEquals(WORDED.toSet(), strings.readErrors.keys)
        val texts = WORDED.map { strings.readErrors.getValue(it) } +
            strings.readFallback + strings.moreFallback + strings.detailFallback
        assertEquals(texts.size, texts.toSet().size, "every worded failure must have its own text")
    }

    @Test
    fun `every failure reading the history is explained`() {
        WORDED.forEach { assertLogError(it, strings.readErrors.getValue(it)) }
        FALLBACK.forEach { assertLogError(it, strings.readFallback) }
    }

    @Test
    fun `retry is worded`() {
        compose.setLogScreen(LogState(directory = ROOT, loading = false, error = GitFailure.Unreachable))
        compose.onNodeWithText(strings.retry).assertIsDisplayed()
    }

    @Test
    fun `a failed page is worded`() {
        compose.setLogScreen(loadedLog.copy(moreError = GitFailure.Unknown))
        compose.scrollLogTo(hasTestTag(LogTags.MORE_ERROR))
        compose.onNode(hasText(strings.moreFallback) and hasTestTag(LogTags.MORE_ERROR)).assertIsDisplayed()
    }

    @Test
    fun `the commit detail is worded`() {
        compose.setDetailScreen(loadedDetail)

        compose.onNodeWithText(strings.detailTitle("0123456")).assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.AUTHOR))
        compose.onNode(hasTestTag(CommitDetailTags.AUTHOR) and hasText(strings.author, substring = true)).assertIsDisplayed()
        compose.onNode(
            hasTestTag(CommitDetailTags.AUTHOR) and hasText(expectedDate(AUTHORED, strings.locale), substring = true),
        ).assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.COMMITTER))
        compose.onNode(hasTestTag(CommitDetailTags.COMMITTER) and hasText(strings.committer, substring = true))
            .assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        compose.onNode(hasTestTag(CommitDetailTags.PARENTS) and hasText(strings.parent)).assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.FILES_HEADER))
        compose.onNode(hasTestTag(CommitDetailTags.FILES_HEADER) and hasText(strings.files(4))).assertIsDisplayed()
    }

    @Test
    fun `the files of a commit are worded`() {
        compose.setDetailScreen(loadedDetail)

        val text = hasTestTag(CommitDetailTags.FILE) and hasText("Giriş Ekranı.kt", substring = true)
        compose.scrollDetailTo(text)
        compose.onNode(text and hasContentDescription(strings.counts(12, 3))).assertIsDisplayed()
        val binary = hasTestTag(CommitDetailTags.FILE) and hasText(strings.binary, substring = true)
        compose.scrollDetailTo(binary)
        compose.onNode(binary).assertIsDisplayed()
        val renamed = hasTestTag(CommitDetailTags.FILE) and hasText(strings.renamedFrom("lib/old name.txt"), substring = true)
        compose.scrollDetailTo(renamed)
        compose.onNode(renamed).assertIsDisplayed()
    }

    @Test
    fun `one added line is worded for one`() {
        val file = CommitFileChange("a.txt", GitChangeKind.Modified, additions = 1, deletions = 1)
        compose.setDetailScreen(loadedDetail.copy(detail = detail.copy(files = listOf(file))))

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.FILES_HEADER))
        compose.onNode(hasTestTag(CommitDetailTags.FILES_HEADER) and hasText(strings.files(1))).assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.FILE))
        compose.onNode(hasContentDescription(strings.counts(1, 1))).assertIsDisplayed()
    }

    @Test
    fun `a merge and a root commit are worded`() {
        val shown = mutableStateOf(loadedDetail.copy(detail = detail.copy(summary = mergeCommit)))
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface { CommitDetailScreen(route = detailRoute, state = shown.value, onIntent = {}) }
            }
        }
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        compose.onNode(hasTestTag(CommitDetailTags.PARENTS) and hasText(strings.parents, substring = true)).assertIsDisplayed()
        compose.onNode(hasTestTag(CommitDetailTags.PARENTS) and hasText(strings.merge, substring = true)).assertIsDisplayed()

        shown.value = loadedDetail.copy(detail = detail.copy(summary = plainCommit.copy(parents = emptyList())))
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        compose.onNode(hasTestTag(CommitDetailTags.PARENTS) and hasText(strings.root, substring = true)).assertIsDisplayed()
    }

    @Test
    fun `every kind of change is worded`() {
        assertEquals(GitChangeKind.entries.toSet(), strings.kinds.keys)
        assertEquals(GitChangeKind.entries.size, strings.kinds.values.toSet().size, "kinds must differ")
        val files = GitChangeKind.entries.mapIndexed { index, kind ->
            CommitFileChange("dir/file-$index.txt", kind, additions = 1, deletions = 1)
        }
        compose.setDetailScreen(loadedDetail.copy(detail = detail.copy(files = files)))

        files.forEach { file ->
            val row = hasTestTag(CommitDetailTags.FILE) and hasText(file.path.substringAfterLast('/'), substring = true) and
                hasText(strings.kinds.getValue(file.kind), substring = true)
            compose.scrollDetailTo(row)
            compose.onNode(row).assertIsDisplayed()
        }
    }

    @Test
    fun `the commit detail's loading and failures are worded`() {
        val shown = mutableStateOf(loadedDetail.copy(loading = true, detail = null))
        compose.setContent {
            PhopencodeTheme(darkTheme = false) {
                Surface { CommitDetailScreen(route = detailRoute, state = shown.value, onIntent = {}) }
            }
        }
        compose.onNodeWithContentDescription(strings.detailLoading).assertIsDisplayed()

        WORDED.forEach { failure ->
            shown.value = loadedDetail.copy(detail = null, error = failure)
            compose.onAllNodesWithText(strings.readErrors.getValue(failure)).assertCountEquals(1)
        }
        FALLBACK.forEach { failure ->
            shown.value = loadedDetail.copy(detail = null, error = failure)
            compose.onAllNodesWithText(strings.detailFallback).assertCountEquals(1)
        }
    }

    /** Content can be set once per rule: a state holder swaps the state shown for each failure. */
    private val shownLog = mutableStateOf<LogState?>(null)
    private var started = false

    private fun assertLogError(failure: GitFailure, message: String) {
        shownLog.value = LogState(directory = ROOT, loading = false, error = failure)
        if (!started) {
            started = true
            compose.setContent {
                PhopencodeTheme(darkTheme = false) {
                    Surface { shownLog.value?.let { LogScreen(route = logRoute, state = it, onIntent = {}) } }
                }
            }
        }
        compose.onNodeWithTag(LogTags.ERROR).assertIsDisplayed()
        compose.onAllNodesWithText(message).assertCountEquals(1)
    }
}
