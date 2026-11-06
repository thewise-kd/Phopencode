package io.github.thewisekd.phopencode.feature.git.ui.log

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the commit detail screen draws and sends; the texts are in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en")
class CommitDetailScreenBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<CommitDetailIntent>()

    private fun show(state: CommitDetailState) = compose.setDetailScreen(state) { sent += it }

    private fun fileRow(name: String): SemanticsMatcher = hasTestTag(CommitDetailTags.FILE) and hasText(name, substring = true)

    private fun inSection(tag: String, text: String): SemanticsMatcher = hasTestTag(tag) and hasText(text, substring = true)

    @Test
    fun `the root keeps the route's placeholder tag when loaded`() {
        show(loadedDetail)
        compose.onNodeWithTag("placeholder:$detailRoute").assertIsDisplayed()
    }

    @Test
    fun `the title names the short hash`() {
        show(loadedDetail)
        compose.onNodeWithText("Commit 0123456").assertIsDisplayed()
    }

    @Test
    fun `loading shows a still line`() {
        show(CommitDetailState(commit = HASH))

        compose.onNodeWithTag(CommitDetailTags.LOADING).assertIsDisplayed()
        compose.onNodeWithContentDescription("Reading the commit…").assertIsDisplayed()
        compose.onNodeWithTag(CommitDetailTags.LIST).assertDoesNotExist()
    }

    @Test
    fun `an unknown commit is explained and retried`() {
        show(CommitDetailState(commit = HASH, loading = false, error = GitFailure.NotFound))

        compose.onNodeWithTag(CommitDetailTags.ERROR).assertIsDisplayed()
        compose.onNodeWithText("The commit or branch is no longer there.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf<CommitDetailIntent>(CommitDetailIntent.Retry), sent)
    }

    @Test
    fun `an unworded failure takes the fallback and a lost login offers no retry`() {
        show(CommitDetailState(commit = HASH, loading = false, error = GitFailure.Unauthorized))
        compose.onNodeWithText("The server refused the login. Go back and connect again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `an unworded failure reading the commit takes its fallback`() {
        show(CommitDetailState(commit = HASH, loading = false, error = GitFailure.Unknown))
        compose.onNodeWithText("The commit could not be read.").assertIsDisplayed()
    }

    @Test
    fun `the full message and hash are shown`() {
        show(loadedDetail)

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.MESSAGE))
        compose.onNode(inSection(CommitDetailTags.MESSAGE, plainCommit.subject)).assertIsDisplayed()
        compose.onNode(inSection(CommitDetailTags.MESSAGE, "Giriş ekranındaki hata düzeltildi.\n\nİkinci paragraf."))
            .assertIsDisplayed()
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.HASH))
        compose.onNode(inSection(CommitDetailTags.HASH, HASH)).assertIsDisplayed()
    }

    @Test
    fun `the author and committer are shown with their dates`() {
        show(loadedDetail)

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.AUTHOR))
        for (part in listOf("Author", "Ayşe Örnek", "ayse@example.invalid", expectedDate(AUTHORED, Locale.ENGLISH))) {
            compose.onNode(inSection(CommitDetailTags.AUTHOR, part)).assertIsDisplayed()
        }
        compose.scrollDetailTo(hasTestTag(CommitDetailTags.COMMITTER))
        for (part in listOf("Committer", "Cem Committer", "cem@example.invalid", expectedDate(COMMITTED, Locale.ENGLISH))) {
            compose.onNode(inSection(CommitDetailTags.COMMITTER, part)).assertIsDisplayed()
        }
    }

    @Test
    fun `one parent is named by its short hash`() {
        show(loadedDetail)

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        compose.onNode(inSection(CommitDetailTags.PARENTS, "Parent")).assertIsDisplayed()
        compose.onNode(inSection(CommitDetailTags.PARENTS, "aaaaaaa")).assertIsDisplayed()
        compose.onNode(inSection(CommitDetailTags.PARENTS, "Merge")).assertDoesNotExist()
    }

    @Test
    fun `a merge names both parents and is marked`() {
        show(loadedDetail.copy(detail = detail.copy(summary = mergeCommit)))

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        for (part in listOf("Parents", "aaaaaaa", "bbbbbbb", "Merge")) {
            compose.onNode(inSection(CommitDetailTags.PARENTS, part)).assertIsDisplayed()
        }
    }

    @Test
    fun `a root commit says it has no parents`() {
        show(loadedDetail.copy(detail = detail.copy(summary = plainCommit.copy(parents = emptyList()))))

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.PARENTS))
        compose.onNode(inSection(CommitDetailTags.PARENTS, "Root commit, no parents")).assertIsDisplayed()
    }

    @Test
    fun `the files are counted and each shows its line counts`() {
        show(loadedDetail)

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.FILES_HEADER))
        compose.onNode(inSection(CommitDetailTags.FILES_HEADER, "4 files changed")).assertIsDisplayed()

        val row = fileRow("Giriş Ekranı.kt")
        compose.scrollDetailTo(row)
        compose.onNode(row and hasText("Modified", substring = true)).assertIsDisplayed()
        compose.onNode(row and hasText("src/main", substring = true)).assertIsDisplayed()
        compose.onNode(row and hasContentDescription("12 lines added, 3 lines removed")).assertIsDisplayed()

        val one = fileRow("one.md")
        compose.scrollDetailTo(one)
        compose.onNode(one and hasContentDescription("1 line added, 0 lines removed"))
            .assertIsDisplayed()
    }

    @Test
    fun `a binary file is marked without counts`() {
        show(loadedDetail.copy(detail = detail.copy(files = listOf(binaryFile))))

        val row = fileRow("logo.png")
        compose.scrollDetailTo(row)
        compose.onNode(row and hasText("Binary", substring = true)).assertIsDisplayed()
        compose.onAllNodesWithTag(CommitDetailTags.COUNTS, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a rename names the path it came from`() {
        show(loadedDetail)

        val row = fileRow("new name.txt")
        compose.scrollDetailTo(row)
        compose.onNode(row and hasText("from lib/old name.txt", substring = true)).assertIsDisplayed()
        compose.onNode(row and hasText("Renamed", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `tapping a file opens its diff with the exact path`() {
        show(loadedDetail)

        val row = fileRow("Giriş Ekranı.kt")
        compose.scrollDetailTo(row)
        compose.onNode(row).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        val renamed = fileRow("new name.txt")
        compose.scrollDetailTo(renamed)
        compose.onNode(renamed).performClick()

        assertEquals(
            listOf<CommitDetailIntent>(
                CommitDetailIntent.OpenFile("src/main/Giriş Ekranı.kt"),
                CommitDetailIntent.OpenFile("lib/new name.txt"),
            ),
            sent.toList(),
        )
        assertEquals(listOf("src/main/Giriş Ekranı.kt", "lib/new name.txt"), sent.map { (it as CommitDetailIntent.OpenFile).path })
    }

    @Test
    fun `a commit without files says so`() {
        show(loadedDetail.copy(detail = detail.copy(files = emptyList<CommitFileChange>())))

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.NO_FILES))
        compose.onNodeWithText("This commit changed no files.").assertIsDisplayed()
    }

    @Test
    fun `an empty body adds no text`() {
        show(loadedDetail.copy(detail = detail.copy(body = "")))

        compose.scrollDetailTo(hasTestTag(CommitDetailTags.MESSAGE))
        compose.onNode(inSection(CommitDetailTags.MESSAGE, plainCommit.subject)).assertIsDisplayed()
        compose.onNodeWithText("İkinci paragraf.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `back closes the screen`() {
        show(loadedDetail)
        compose.onNodeWithContentDescription("Go back").performClick()
        assertEquals(listOf<CommitDetailIntent>(CommitDetailIntent.Close), sent)
        assertTrue(sent.none { it is CommitDetailIntent.OpenFile })
    }
}
