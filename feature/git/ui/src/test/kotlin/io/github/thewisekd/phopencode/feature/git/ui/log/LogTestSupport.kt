package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.material3.Surface
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import io.github.thewisekd.phopencode.core.navigation.GitRoute
import io.github.thewisekd.phopencode.core.ui.theme.PhopencodeTheme
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetail
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitSummary
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

/** Made-up paths, people and branch names: no real server layout. */
internal const val ROOT = "/work/demo proje"
internal const val HASH = "0123456789abcdef0123456789abcdef01234567"
internal const val PARENT_A = "aaaaaaa1111111111111111111111111111111111"
internal const val PARENT_B = "bbbbbbb2222222222222222222222222222222222"
internal const val MERGE_HASH = "fedcba9876543210fedcba9876543210fedcba98"

/** Noon in UTC: the same day in every time zone the tests may run in. */
internal val AUTHORED: Instant = Instant.parse("2026-01-15T12:00:00Z")
internal val COMMITTED: Instant = Instant.parse("2026-01-16T12:00:00Z")

internal val logRoute = GitRoute.Log(ROOT)
internal val detailRoute = GitRoute.CommitDetail(ROOT, HASH)

internal val plainCommit = CommitSummary(
    hash = HASH,
    parents = listOf(PARENT_A),
    subject = "Düzeltme: giriş ekranı",
    authorName = "Ayşe Örnek",
    authorEmail = "ayse@example.invalid",
    authoredAt = AUTHORED,
)

internal val mergeCommit = CommitSummary(
    hash = MERGE_HASH,
    parents = listOf(PARENT_A, PARENT_B),
    subject = "Join the feature work",
    authorName = "Bob Example",
    authorEmail = "bob@example.invalid",
    authoredAt = AUTHORED,
)

internal val loadedLog = LogState(
    directory = ROOT,
    branches = listOf("main", "feature/giriş", "origin/main"),
    commits = listOf(plainCommit, mergeCommit),
    loading = false,
)

/** [count] commits with distinct hashes and subjects, more than a small screen shows. */
internal fun manyCommits(count: Int): List<CommitSummary> = (0 until count).map { index ->
    plainCommit.copy(hash = "%040x".format(index + 1), subject = "Commit number $index")
}

internal val textFile = CommitFileChange("src/main/Giriş Ekranı.kt", GitChangeKind.Modified, additions = 12, deletions = 3)
internal val addedFile = CommitFileChange("docs/one.md", GitChangeKind.Added, additions = 1, deletions = 0)
internal val binaryFile = CommitFileChange("assets/logo.png", GitChangeKind.Added)
internal val renamedFile = CommitFileChange(
    "lib/new name.txt",
    GitChangeKind.Renamed,
    oldPath = "lib/old name.txt",
    additions = 0,
    deletions = 0,
)

internal val detail = CommitDetail(
    summary = plainCommit,
    body = "Giriş ekranındaki hata düzeltildi.\n\nİkinci paragraf.",
    committerName = "Cem Committer",
    committerEmail = "cem@example.invalid",
    committedAt = COMMITTED,
    files = listOf(textFile, addedFile, binaryFile, renamedFile),
)

internal val loadedDetail = CommitDetailState(commit = HASH, loading = false, detail = detail)

internal fun ComposeContentTestRule.setLogScreen(state: LogState, onIntent: (LogIntent) -> Unit = {}) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { LogScreen(route = logRoute, state = state, onIntent = onIntent) }
        }
    }
}

internal fun ComposeContentTestRule.setDetailScreen(
    state: CommitDetailState,
    onIntent: (CommitDetailIntent) -> Unit = {},
) {
    setContent {
        PhopencodeTheme(darkTheme = false) {
            Surface { CommitDetailScreen(route = detailRoute, state = state, onIntent = onIntent) }
        }
    }
}

/** The lists compose only the rows in view: scroll them until [matcher]'s node exists. */
internal fun ComposeContentTestRule.scrollLogTo(matcher: SemanticsMatcher) {
    onNodeWithTag(LogTags.LIST).performScrollToNode(matcher)
}

internal fun ComposeContentTestRule.scrollDetailTo(matcher: SemanticsMatcher) {
    onNodeWithTag(CommitDetailTags.LIST).performScrollToNode(matcher)
}

/** How the screens should print a date in [locale], in this JVM's time zone. */
internal fun expectedDate(instant: Instant, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(instant.toEpochMilliseconds()))
